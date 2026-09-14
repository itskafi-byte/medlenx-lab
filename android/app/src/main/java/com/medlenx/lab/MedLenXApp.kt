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
    }
}
