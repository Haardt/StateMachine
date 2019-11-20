package com.tinder

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.cast
import reactor.kotlin.core.publisher.toMono
import reactor.util.function.Tuple2
import reactor.kotlin.core.util.function.component1
import reactor.kotlin.core.util.function.component2

typealias TransitionAction<CONTEXT, EVENT> = (CONTEXT, EVENT) -> Mono<CONTEXT>


class StateMachine<STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : TransitionAction<CONTEXT, EVENT>> private constructor(
        private val graph: Graph<STATE, EVENT, CONTEXT, SIDE_EFFECT>) {

    fun transition(startState: STATE, initContext: CONTEXT, event: EVENT): Mono<Pair<STATE, CONTEXT>> {
        return startState.getTransition(initContext, event).zipWith(Mono.just(initContext))
                .filter { it.t1 is Transition.Valid }
                .cast<Tuple2<Transition.Valid<STATE, EVENT, SIDE_EFFECT>, CONTEXT>>()
                .flatMap { (transition, context) ->
                    Mono.zip(transition.toMono(), transition.sideEffect?.invoke(context, event)
                            ?: Mono.just(context))
                }
                .flatMap { (transition, context) ->
                    with(transition) {
                        with(fromState) {
                            notifyOnExit(context, event).map { transition.toState to it }
                        }
                        with(toState) {
                            notifyOnEnter(context, event).map { transition.toState to it }
                        }
                    }
                }.switchIfEmpty(Mono.error(IllegalStateException("No transition in state ($startState) for  event ($event) found!")))
    }

    private fun STATE.getTransition(context: CONTEXT, event: EVENT): Mono<Transition<STATE, EVENT, SIDE_EFFECT>> {
        for ((eventMatcher, createTransitionTo) in getDefinition().transitions) {
            if (eventMatcher.matches(event)) {
                return createTransitionTo(this, context, event).map { (toState, sideEffect) ->
                    Transition.Valid(this, event, toState, sideEffect)
                }
            }
        }
        return Transition.Invalid<STATE, EVENT, SIDE_EFFECT>(this, event).toMono()
    }

    private fun STATE.getDefinition() = graph.stateDefinitions
            .filter { it.key.matches(this) }
            .map { it.value }
            .firstOrNull() ?: error("Missing definition for state ${this.javaClass.simpleName}!")

    private fun STATE.notifyOnEnter(context: CONTEXT, cause: EVENT): Mono<CONTEXT> {
        return Flux.concat(*getDefinition().onEnterListeners.map { it(context, this, cause) }.toTypedArray()).last(context)
    }

    private fun STATE.notifyOnExit(context: CONTEXT, cause: EVENT): Mono<CONTEXT> {
        return Flux.concat(*getDefinition().onExitListeners.map { it(context, this, cause) }.toTypedArray()).last(context)
    }

    private fun Transition<STATE, EVENT, SIDE_EFFECT>.notifyOnTransition() {
        graph.onTransitionListeners.forEach { it(this) }
    }

    @Suppress("UNUSED")
    sealed class Transition<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> {
        abstract val fromState: STATE
        abstract val event: EVENT

        data class Valid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
                override val fromState: STATE,
                override val event: EVENT,
                val toState: STATE,
                val sideEffect: SIDE_EFFECT?
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()

        data class Invalid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
                override val fromState: STATE,
                override val event: EVENT
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()
    }

    data class Graph<STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : Any>(
            val stateDefinitions: Map<Matcher<STATE, STATE>, State<STATE, EVENT, CONTEXT, SIDE_EFFECT>>,
            val onTransitionListeners: List<(Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit>
    ) {

        class State<STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : Any> internal constructor() {
            val onEnterListeners = mutableListOf<(CONTEXT, STATE, EVENT) -> Mono<CONTEXT>>()
            val onExitListeners = mutableListOf<(CONTEXT, STATE, EVENT) -> Mono<CONTEXT>>()
            val transitions = linkedMapOf<Matcher<EVENT, EVENT>, (STATE, CONTEXT, EVENT) -> Mono<TransitionTo<STATE, SIDE_EFFECT>>>()

            data class TransitionTo<out STATE : Any, out SIDE_EFFECT : Any> internal constructor(
                    val toState: STATE,
                    val sideEffect: SIDE_EFFECT?
            )
        }
    }

    class Matcher<T : Any, out R : T> private constructor(private val clazz: Class<R>) {

        private val predicates = mutableListOf<(T) -> Boolean>({ clazz.isInstance(it) })

        fun where(predicate: R.() -> Boolean): Matcher<T, R> = apply {
            predicates.add {
                @Suppress("UNCHECKED_CAST")
                (it as R).predicate()
            }
        }

        fun matches(value: T) = predicates.all { it(value) }

        companion object {
            fun <T : Any, R : T> any(clazz: Class<R>): Matcher<T, R> = Matcher(clazz)

            inline fun <T : Any, reified R : T> any(): Matcher<T, R> = any(R::class.java)

            inline fun <T : Any, reified R : T> eq(value: R): Matcher<T, R> = any<T, R>().where { this == value }
        }
    }

    class GraphBuilder<STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : Any>(
            graph: Graph<STATE, EVENT, CONTEXT, SIDE_EFFECT>? = null
    ) {
        private val stateDefinitions = LinkedHashMap(graph?.stateDefinitions ?: emptyMap())
        private val onTransitionListeners = ArrayList(graph?.onTransitionListeners ?: emptyList())

        fun <S : STATE> state(
                stateMatcher: Matcher<STATE, S>,
                init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            stateDefinitions[stateMatcher] = StateDefinitionBuilder<S>().apply(init).build()
        }

        inline fun <reified S : STATE> state(noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            state(Matcher.any(), init)
        }

        inline fun <reified S : STATE> state(state: S, noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            state(Matcher.eq<STATE, S>(state), init)
        }

        fun onTransition(listener: (Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit) {
            onTransitionListeners.add(listener)
        }

        fun build(): Graph<STATE, EVENT, CONTEXT, SIDE_EFFECT> {
            return Graph(stateDefinitions.toMap(), onTransitionListeners.toList())
        }

        inner class StateDefinitionBuilder<S : STATE> {

            private val stateDefinition = Graph.State<STATE, EVENT, CONTEXT, SIDE_EFFECT>()

            inline fun <reified E : EVENT> any(): Matcher<EVENT, E> = Matcher.any()

            inline fun <reified R : EVENT> eq(value: R): Matcher<EVENT, R> = Matcher.eq(value)

            fun <E : EVENT> on(
                    eventMatcher: Matcher<EVENT, E>,
                    createTransitionTo: S.(CONTEXT, E) -> Mono<Graph.State.TransitionTo<STATE, SIDE_EFFECT>>
            ) {
                stateDefinition.transitions[eventMatcher] = { state, context, event ->
                    @Suppress("UNCHECKED_CAST")
                    createTransitionTo((state as S), context, event as E)
                }
            }

            inline fun <reified E : EVENT> on(
                    noinline createTransitionTo: S.(CONTEXT, E) -> Mono<Graph.State.TransitionTo<STATE, SIDE_EFFECT>>
            ) {
                return on(any(), createTransitionTo)
            }

            inline fun <reified E : EVENT> on(
                    event: E,
                    noinline createTransitionTo: S.(CONTEXT, E) -> Mono<Graph.State.TransitionTo<STATE, SIDE_EFFECT>>
            ) {
                return on(eq(event), createTransitionTo)
            }

            fun onEnter(listener: S.(CONTEXT, EVENT) -> Mono<CONTEXT>) = with(stateDefinition) {
                onEnterListeners.add { context, state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, context, cause)
                }
            }

            fun onExit(listener: S.(CONTEXT, EVENT) -> Mono<CONTEXT>) = with(stateDefinition) {
                onExitListeners.add { context, state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, context, cause)
                }
            }

            fun build() = stateDefinition

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.transitionTo(state: STATE, sideEffect: SIDE_EFFECT? = null) =
                    Graph.State.TransitionTo(state, sideEffect)

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.dontTransition(sideEffect: SIDE_EFFECT? = null) = transitionTo(this, sideEffect)
        }
    }

    companion object {
        fun <STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : TransitionAction<CONTEXT, EVENT>> create(
                init: GraphBuilder<STATE, EVENT, CONTEXT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, CONTEXT, SIDE_EFFECT> {
            return create(null, init)
        }

        private fun <STATE : Any, EVENT : Any, CONTEXT, SIDE_EFFECT : TransitionAction<CONTEXT, EVENT>> create(
                graph: Graph<STATE, EVENT, CONTEXT, SIDE_EFFECT>?,
                init: GraphBuilder<STATE, EVENT, CONTEXT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, CONTEXT, SIDE_EFFECT> {
            return StateMachine(GraphBuilder(graph).apply(init).build())
        }
    }
}
