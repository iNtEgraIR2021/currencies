package de.salomax.currencies.repository

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.*
import com.github.kittinunf.fuel.moshi.moshiDeserializerOf
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import com.squareup.moshi.*
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.salomax.currencies.model.ExchangeRates
import de.salomax.currencies.model.Timeline
import de.salomax.currencies.model.Rate
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

object ExchangeRatesService {

    enum class ApiProvider(val baseUrl: String) {
        EXCHANGERATE_HOST("https://api.exchangerate.host"),
        FRANKFURTER_APP("https://api.frankfurter.app"),
        FER_EE("https://api.fer.ee")
    }

    /**
     * Get all the current exchange rates from the given api provider. Base will be Euro.
     */
    suspend fun getRates(apiProvider: ApiProvider): Result<ExchangeRates, FuelError> {
        // Currency conversions are done relatively to each other - so it basically doesn't matter
        // which base is used here. However, Euro is a strong currency, preventing rounding errors.
        val base = "EUR"

        return Fuel.get(
            when (apiProvider) {
                ApiProvider.EXCHANGERATE_HOST -> "${apiProvider.baseUrl}/latest" +
                        "?base=$base" +
                        "&v=${UUID.randomUUID()}"
                ApiProvider.FRANKFURTER_APP -> "${apiProvider.baseUrl}/latest" +
                        "?base=$base"
                ApiProvider.FER_EE -> "${apiProvider.baseUrl}/latest" +
                        "?base=$base"
            }
        ).awaitResult(
            moshiDeserializerOf(
                Moshi.Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .add(RatesAdapter(base))
                    .add(LocalDateAdapter())
                    .build()
                    .adapter(ExchangeRates::class.java)
            )
        )
    }

    /**
     * Get the historic rates of the past year between the given base and symbol.
     * Won't get all the symbols, as it makes a big difference in transferred data size:
     * ~12KB for one symbol to ~840KB for all symbols
     */
    suspend fun getTimeline(apiProvider: ApiProvider, base: String, symbol: String): Result<Timeline, FuelError> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusYears(1)

        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        // can't search for FOK - have to use DKK instead
        val parameterBase = if (base == "FOK") "DKK" else base
        val parameterSymbol = if (symbol == "FOK") "DKK" else symbol
        // call api
        return Fuel.get(
            when (apiProvider) {
                ApiProvider.EXCHANGERATE_HOST -> "${apiProvider.baseUrl}/timeseries" +
                        "?base=$parameterBase" +
                        "&v=${UUID.randomUUID()}" +
                        "&start_date=${startDate.format(dateFormatter)}" +
                        "&end_date=${endDate.format(dateFormatter)}" +
                        "&symbols=$parameterSymbol"
                ApiProvider.FRANKFURTER_APP -> "${apiProvider.baseUrl}/" +
                        startDate.format(dateFormatter) +
                        ".." +
                        endDate.format(dateFormatter) +
                        "?base=$parameterBase" +
                        "&symbols=$parameterSymbol"
                ApiProvider.FER_EE -> "${apiProvider.baseUrl}/" +
                        startDate.format(dateFormatter) +
                        ".." +
                        endDate.format(dateFormatter) +
                        "?base=$parameterBase" +
                        "&symbols=$parameterSymbol"
            }
        ).awaitResult(
            moshiDeserializerOf(
                Moshi.Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .add(RatesAdapter(base))
                    .add(LocalDateAdapter())
                    .add(TimelineRatesToRateAdapter(symbol))
                    .build()
                    .adapter(Timeline::class.java)
            )
        ).map { timeline ->
            // change back base to original one
            when (base) {
                "FOK" -> timeline.copy(base = base)
                else -> timeline
            }
        }
    }

    /*
     * Converts a timeline rates object to a Map<LocalDate, Rate?>>
     * The API actually returns Map<LocalDate, List<Rate>>>, however, we only want one Rate per day.
     * This converter reduces the list.
     */
    internal class TimelineRatesToRateAdapter(private val symbol: String): JsonAdapter<Map<LocalDate, Rate>>() {

        @Synchronized
        @FromJson
        override fun fromJson(reader: JsonReader): Map<LocalDate, Rate> {
            val map = mutableMapOf<LocalDate, Rate>()
            reader.beginObject()
            // convert
            while (reader.hasNext()) {
                val date: LocalDate = LocalDate.parse(reader.nextName())
                var rate: Rate? = null
                reader.beginObject()
                // sometimes there's no rate yet, but an empty body or more than one rate, so check first
                while (reader.hasNext() && reader.peek() == JsonReader.Token.NAME) {
                    val name = reader.nextName()
                    val value = reader.nextDouble().toFloat()
                    rate =
                        // change dkk to fok, when needed
                        if (name == "DKK" && symbol == "FOK")
                            Rate("FOK", value)
                        // make sure that the symbol matches the one we requested
                        else if (name == symbol)
                            Rate(name, value)
                        else
                            null
                }
                if (rate != null)
                    map[date] = rate
                reader.endObject()
            }
            reader.endObject()
            return map
        }

        @Synchronized
        @ToJson
        @Throws(IOException::class)
        override fun toJson(writer: JsonWriter, value: Map<LocalDate, Rate>?) {
            writer.nullValue()
        }

    }

    /*
     * Converts currency object to array of currencies.
     * Also removes some unwanted values and adds some wanted ones.
     */
    internal class RatesAdapter(private val base: String) : JsonAdapter<List<Rate>>() {

        @Synchronized
        @FromJson
        @Suppress("SpellCheckingInspection")
        @Throws(IOException::class)
        override fun fromJson(reader: JsonReader): List<Rate> {
            val list = mutableListOf<Rate>()
            reader.beginObject()
            // convert
            while (reader.hasNext()) {
                val name: String = reader.nextName()
                val value: Double = reader.nextDouble()
                // filter these:
                if (name != "BTC" // Bitcoin
                    && name != "CLF" // Unidad de Fomento
                    && name != "XDR" // special drawing rights
                    && name != "XAG" // silver
                    && name != "XAU" // gold
                    && name != "XPD" // palladium
                    && name != "XPT" // platinum
                    && name != "MRO" // Mauritanian ouguiya (pre-2018)
                    && name != "STD" // São Tomé and Príncipe dobra (pre-2018)
                    && name != "VEF" // Venezuelan bolívar fuerte (old)
                    && name != "CNH" // Chinese renminbi (Offshore)
                    && name != "CUP" // Cuban peso (moneda nacional)
                ) {
                    list.add(Rate(name, value.toFloat()))
                }
            }
            reader.endObject()
            // add base - but only if it's missing in the api response!
            if (list.find { rate -> rate.code == base } == null)
                list.add(Rate(base, 1f))
            // also add Faroese króna (same as Danish krone) if it isn't already there - I simply like it!
            if (list.find { it.code == "FOK" } == null)
                list.find { it.code == "DKK" }?.value?.let { dkk ->
                    list.add(Rate("FOK", dkk))
                }
            return list
        }

        @Synchronized
        @ToJson
        @Throws(IOException::class)
        override fun toJson(writer: JsonWriter, value: List<Rate>?) {
            writer.nullValue()
        }

    }

    internal class LocalDateAdapter : JsonAdapter<LocalDate>() {

        @Synchronized
        @FromJson
        @Throws(IOException::class)
        override fun fromJson(reader: JsonReader): LocalDate? {
            return LocalDate.parse(reader.nextString())
        }

        @Synchronized
        @ToJson
        @Throws(IOException::class)
        override fun toJson(writer: JsonWriter, value: LocalDate?) {
            writer.value(value?.toString())
        }

    }
}