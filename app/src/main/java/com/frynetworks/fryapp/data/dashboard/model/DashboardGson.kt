package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import java.math.BigDecimal

/**
 * Shared Gson instance for every dashboard response DTO. Lenient (tolerates the odd malformed
 * document without hard-failing the whole parse) and registers an explicit [BigDecimal]
 * deserializer so every amount-bearing field is read from the raw JSON number text — never
 * routed through [Double], which cannot represent `12.34`-shaped dashboard amounts exactly.
 */
object DashboardGson {
    val instance: Gson = GsonBuilder()
        .setLenient()
        .registerTypeAdapter(
            BigDecimal::class.java,
            JsonDeserializer { json, _, _ ->
                if (json.isJsonNull) null else json.asJsonPrimitive.asBigDecimal
            },
        )
        .create()
}
