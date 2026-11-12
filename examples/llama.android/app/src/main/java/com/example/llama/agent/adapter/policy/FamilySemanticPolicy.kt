package com.example.llama.agent.adapter.policy

import android.content.Context

/**
 * Samostatná sémantická politika rodiny modelu.
 * Definuje POUZE fakta specifická pro danou rodinu (časové ukotvení a případnou uživatelskou zprávu).
 * NEPOUŽÍVÁ ŽÁDNÉ MANIPULAČNÍ DIREKTIVY ani příkazy, které by ovlivňovaly autonomní rozhodování modelu.
 */
interface FamilySemanticPolicy {
    val familyName: String

    /**
     * Sestaví systémovou hlavičku (faktické časové ukotvení a volitelná uživatelská zpráva).
     */
    fun buildSystemHeader(context: Context, userSystemMessage: String): String

    /**
     * Získá specifikaci nástrojů ve formátu nativním pro danou rodinu.
     */
    fun getToolInstructions(): String = com.example.llama.agent.tools.web.WebToolsDispatcher.getActiveToolInstructions()
}
