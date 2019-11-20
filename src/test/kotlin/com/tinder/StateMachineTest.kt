package com.tinder

import org.amshove.kluent.`should be`
import org.amshove.kluent.`should not be`
import org.spekframework.spek2.Spek
import org.spekframework.spek2.lifecycle.CachingMode
import org.spekframework.spek2.style.gherkin.Feature
import reactor.kotlin.core.publisher.toMono


sealed class State {
    object Solid : State()
    object Liquid : State()
    object Gas : State()
}

sealed class Event {
    object OnMelted : Event()
    object OnFrozen : Event()
    object OnVaporized : Event()
    object OnCondensed : Event()
}

data class Context(val value: String)

internal class StateMachineTest : Spek({

    Feature("Test simple state machine") {

        val stateMachine by memoized(CachingMode.SCOPE) {
            StateMachine.create<State, Event, Context, TransitionAction<Context, Event>> {
                state<State.Solid> {
                    on<Event.OnMelted> { _, _ ->
                        transitionTo(State.Liquid) { context, _ ->
                            context.copy(value = "melted").toMono()
                        }.toMono()
                    }
                    state<State.Liquid> {
                        on<Event.OnFrozen> { _, _ ->
                            transitionTo(State.Solid) { context, _ ->
                                context.copy(value = "forzen").toMono()
                            }.toMono()

                        }
                        on<Event.OnVaporized> { _, _ ->
                            transitionTo(State.Solid) { context, _ ->
                                context.copy(value = "vaporized").toMono()
                            }.toMono()
                        }
                    }
                    state<State.Gas> {
                        on<Event.OnCondensed> { _, _ ->
                            transitionTo(State.Liquid) { context, _ ->
                                context.copy(value = "condensed").toMono()
                            }.toMono()
                        }
                    }
                }
            }
        }

        Scenario("State.Solid to State.Liquid") {
            lateinit var result: Pair<State, Context>

            When("onMelted ") {
                result = stateMachine.transition(State.Solid, Context(""), Event.OnMelted)
                        .block()!!
            }

            Then("it should melted") {
                result.first `should be` State.Liquid
            }

            And("the context should have the value") {
                result.second.value `should be` "melted"
            }
        }
    }
})