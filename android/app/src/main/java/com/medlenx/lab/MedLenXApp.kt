package com.medlenx.lab

import android.app.Application
import com.medlenx.lab.data.config.AppGraph

/**
 * Application entry point and composition root.
 *
 * Dependency wiring is manual rather than Hilt on purpose: this module cannot be
 * compiled in the sandbox that produced it, so keeping codegen to Room alone reduces
 * the number of ways the first build can fail.
 */
class MedLenXApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
        // Kicks off the ~16 MB catalogue import on the graph's IO scope. Nothing
        // else triggers it, and the matcher, the enrichment step and the Hub's
        // drug index all read the resulting Room rows.
        graph.importCatalogue()
        // Registers the connectivity callback and starts the queue-depth / profile
        // collectors. Without it `online` never leaves its initial `true`, the header's
        // "N queued" chip never appears, and a capture parked in a dead zone is never
        // noticed coming back online.
        graph.deviceState.start(graph.scope)
    }
}
