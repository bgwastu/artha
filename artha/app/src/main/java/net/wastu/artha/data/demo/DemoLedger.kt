package net.wastu.artha.data.demo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import org.json.JSONObject
import net.wastu.artha.core.model.Direction
import net.wastu.artha.core.model.Money
import net.wastu.artha.core.model.Provider
import net.wastu.artha.core.model.UnifiedTransaction
import net.wastu.artha.data.analysis.INTEREST_INCOME_CATEGORY
import net.wastu.artha.data.export.ImportedLabels

/**
 * A year of invented activity for demos: made-up shops, employer and amounts that look plausible
 * (paydays, weekday habits, a holiday bonus, trips) without matching any real account.
 */
object DemoLedger {
    private val RECENT_UNLABELLED = java.time.Duration.ofHours(30)

    data class Data(val transactions: List<UnifiedTransaction>, val labels: List<ImportedLabels>)

    /** Weighted merchants; [perDay] is the chance of a visit on a day the shop's [days] allow. */
    private class Shop(
        val names: List<String>, val provider: Provider, val category: String,
        val minRp: Int, val maxRp: Int, val perDay: Double,
        val qris: Boolean = false, val hours: IntRange = 9..20, val weekendOnly: Boolean = false,
        val weekdaysOnly: Boolean = false,
    )

    private val shops = listOf(
        Shop(listOf("Mini Mart Lintang", "Alfa Kita Express", "Toko Segar Sentosa"), Provider.JAGO, "Groceries", 18_000, 190_000, 0.22, qris = true, hours = 17..21),
        Shop(listOf("Pasar Raya Mingguan", "Hyper Sari Fresh"), Provider.JAGO, "Groceries", 150_000, 640_000, 0.06, qris = true, hours = 8..12, weekendOnly = true),
        Shop(listOf("Warung Bu Tini", "Nasi Padang Sinar Minang", "Bakmi Pak Haji", "Soto Kudus Mas Gito"), Provider.JAGO, "Dining Out", 18_000, 65_000, 0.25, qris = true, hours = 11..14, weekdaysOnly = true),
        Shop(listOf("Kopi Kelana", "Senja Coffee Lab", "Teras Kopi"), Provider.JAGO, "Dining Out", 24_000, 58_000, 0.16, qris = true, hours = 8..16),
        Shop(listOf("Rumah Makan Sarasvati", "Steak Rimba Grill", "Ramen Hanabi", "Pizza Nonna Lia"), Provider.JAGO, "Dining Out", 85_000, 340_000, 0.08, qris = true, hours = 18..21, weekendOnly = true),
        Shop(listOf("GrabFood: Ayam Geprek Jaya", "GrabFood: Burger Bangun", "GrabFood: Dimsum Mentari"), Provider.GRAB, "Dining Out", 32_000, 118_000, 0.28, hours = 18..22),
        Shop(listOf("GoFood: Sate Madura Cak Rudi", "GoFood: Bubur Ayam Kencana", "GoFood: Martabak Bangka"), Provider.GOJEK, "Dining Out", 28_000, 98_000, 0.28, hours = 18..22),
        Shop(listOf("GoRide"), Provider.GOJEK, "Transport", 11_000, 48_000, 0.38, weekdaysOnly = true, hours = 7..19),
        Shop(listOf("GrabCar"), Provider.GRAB, "Transport", 34_000, 165_000, 0.12, hours = 8..22),
        Shop(listOf("SPBU Nusantara 31.102", "SPBU Garuda 34.115"), Provider.JAGO, "Transport", 50_000, 160_000, 0.05, qris = true, hours = 7..18),
        Shop(listOf("Tol Lintas Jawa", "Parkir Mall Pelita"), Provider.JAGO, "Transport", 5_000, 62_000, 0.05, hours = 8..20),
        Shop(listOf("Apotek Sehat Sejahtera", "Klinik Pratama Bunda"), Provider.JAGO, "Medical & Health", 28_000, 260_000, 0.045, qris = true, hours = 9..19),
        Shop(listOf("Shopee: Kabel Data Anyaman", "Shopee: Rak Buku Minimalis", "Shopee: Lampu Meja LED", "Shopee: Sepatu Lari Gesit", "Shopee: Tas Selempang Kanvas", "Shopee: Botol Minum Termos"), Provider.SHOPEE, "Shopping & Gear", 39_000, 520_000, 0.22, hours = 10..23),
        Shop(listOf("Barbershop Rapi", "Salon Cantika"), Provider.JAGO, "Personal Care", 45_000, 160_000, 0.045, qris = true, hours = 10..18),
        Shop(listOf("GoMart: Sayur & Buah Segar", "GoMart: Susu dan Roti"), Provider.GOJEK, "Groceries", 35_000, 210_000, 0.12, hours = 17..21),
        Shop(listOf("GoRide Hemat", "GoCar Nyaman"), Provider.GOJEK, "Transport", 16_000, 92_000, 0.14, weekendOnly = true, hours = 9..22),
        Shop(listOf("GoPay Tagihan: Air PDAM", "GoPay Tagihan: BPJS Kesehatan"), Provider.GOJEK, "Utilities & Bills", 85_000, 240_000, 0.02, hours = 8..20),
        Shop(listOf("Gojek: Tiket Bioskop Kota", "Gojek: Konser Musik Akustik"), Provider.GOJEK, "Tech & Subscriptions", 50_000, 220_000, 0.03, weekendOnly = true, hours = 12..19),
        Shop(listOf("GrabMart: Belanja Mingguan", "GrabMart: Snack dan Minuman"), Provider.GRAB, "Groceries", 40_000, 260_000, 0.12, hours = 17..21),
        Shop(listOf("GrabBike", "GrabBike Hemat"), Provider.GRAB, "Transport", 9_000, 36_000, 0.22, weekdaysOnly = true, hours = 6..19),
        Shop(listOf("GrabFood: Kopi Susu Tetangga", "GrabFood: Salad Segar Bowl"), Provider.GRAB, "Dining Out", 22_000, 72_000, 0.14, weekdaysOnly = true, hours = 8..14),
        Shop(listOf("Shopee: Vitamin C dan Zinc", "Shopee: Masker Medis Isi 50"), Provider.SHOPEE, "Medical & Health", 35_000, 180_000, 0.05, hours = 10..22),
        Shop(listOf("Shopee: Sabun dan Sampo Keluarga", "Shopee: Skincare Pagi Malam"), Provider.SHOPEE, "Personal Care", 28_000, 210_000, 0.12, hours = 10..22),
        Shop(listOf("Shopee: Beras 5kg", "Shopee: Minyak Goreng 2L", "Shopee: Kopi Biji Arabika"), Provider.SHOPEE, "Groceries", 60_000, 260_000, 0.12, hours = 10..22),
        Shop(listOf("Shopee: Keyboard Mekanik Tenang", "Shopee: Hub USB-C"), Provider.SHOPEE, "Tech & Subscriptions", 120_000, 750_000, 0.04, hours = 12..23),
        Shop(listOf("Gym Bugar Fit"), Provider.JAGO, "Personal Care", 35_000, 35_000, 0.07, hours = 6..9, weekdaysOnly = true),
    )

    /** Monthly bills as (name, category, rupiah, day of month). */
    private val bills = listOf(
        Bill("Sewa Apartemen Griya Asri", "Utilities & Bills", 3_200_000, 1),
        Bill("Listrik PLN Token", "Utilities & Bills", 420_000, 6),
        Bill("Internet Rumah NusaNet", "Utilities & Bills", 385_000, 9),
        Bill("Paket Data Seluler", "Utilities & Bills", 125_000, 12),
        Bill("Streaming Film Bulanan", "Tech & Subscriptions", 54_000, 14),
        Bill("Penyimpanan Awan 200GB", "Tech & Subscriptions", 45_000, 17),
        Bill("Langganan Musik Premium", "Tech & Subscriptions", 59_000, 20),
    )
    private class Bill(val name: String, val category: String, val rupiah: Long, val day: Int)

    /** The 365 days ending on the day of [now]; the same [seed] always gives the same ledger. */
    fun generate(now: Instant, zone: ZoneId, seed: Long = 7L): Data {
        val rnd = Random(seed)
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays(364)
        val rows = mutableListOf<UnifiedTransaction>()
        val labels = mutableListOf<ImportedLabels>()

        fun add(
            date: LocalDate, hour: Int, provider: Provider, direction: Direction, rupiah: Long, description: String,
            category: String?, payload: String = "{}", qris: Boolean = false,
        ) {
            val at = date.atTime(hour, rnd.nextInt(60), rnd.nextInt(60)).atZone(zone).toInstant()
            if (at.isAfter(now) || date.isBefore(start)) return
            val tx = UnifiedTransaction(
                provider = provider, providerTransactionId = "demo-${rows.size}", direction = direction,
                money = Money(rupiah * 100), occurredAt = at, description = description,
                rawPayload = if (provider == Provider.JAGO) payload else walletPayload(provider, direction, rupiah * 100, at), isQris = qris, category = category, classificationIsManual = category != null,
            )
            rows += tx
            // The newest rows stay unlabelled so the demo AI visibly categorizes them on first open.
            if (category != null && at.isBefore(now.minus(RECENT_UNLABELLED))) labels += ImportedLabels(tx.key, category, null, "Matched the merchant and amount to $category", false)
        }

        fun roundedRupiah(min: Int, max: Int): Long = (min + rnd.nextInt(max - min + 1)).let { if (it < 10_000) it.toLong() else it / 1_000 * 1_000L }

        var date = start
        var salary = 17_500_000L
        while (!date.isAfter(today)) {
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val dom = date.dayOfMonth
            val lastDay = date.lengthOfMonth()
            val month = date.monthValue

            if (dom == minOf(25, lastDay)) {
                if (month == 1) salary += 750_000 // yearly raise
                add(date, 9, Provider.JAGO, Direction.CREDIT, salary + rnd.nextInt(3) * 25_000L, "Gaji PT Cakrawala Digital", "Salary")
                add(date, 10, Provider.JAGO, Direction.DEBIT, 3_000_000, "Pocket Transfer: Dana Darurat", null, """{"code":"SAD01"}""")
                add(date, 10, Provider.JAGO, Direction.CREDIT, 3_000_000, "Pocket Transfer: Dana Darurat", null, """{"code":"SAC01"}""")
                add(date, 10, Provider.JAGO, Direction.DEBIT, 1_000_000, "Top up e-wallet", null, """{"code":"WTD02"}""")
            }
            if (date.monthValue == 12 && dom == 20) add(date, 9, Provider.JAGO, Direction.CREDIT, salary, "Bonus Akhir Tahun PT Cakrawala Digital", "Salary")
            if (date.monthValue == 4 && dom == 5) add(date, 9, Provider.JAGO, Direction.CREDIT, salary, "THR PT Cakrawala Digital", "Salary")
            if (dom == lastDay) {
                val interest = 38_000L + rnd.nextInt(45_000)
                add(date, 2, Provider.JAGO, Direction.CREDIT, interest, "Interest earned", INTEREST_INCOME_CATEGORY, """{"code":"ISC99"}""")
                add(date, 2, Provider.JAGO, Direction.DEBIT, interest / 5, "Tax on interest", "Taxes & Admin", """{"code":"TXD99"}""")
            }
            bills.forEach { bill ->
                if (dom == bill.day) add(date, 8, Provider.JAGO, Direction.DEBIT, bill.rupiah + if (bill.rupiah > 100_000) rnd.nextInt(8) * 5_000L else 0L, bill.name, bill.category)
            }
            if (dom == 15 && rnd.nextInt(10) < 6) add(date, 13, Provider.JAGO, Direction.CREDIT, 1_200_000L + rnd.nextInt(25) * 100_000L, "Proyek Desain Lepas", "Business")
            if (rnd.nextInt(60) == 0) add(date, 19, Provider.JAGO, Direction.CREDIT, 120_000L + rnd.nextInt(8) * 25_000L, "Patungan makan malam", "Reimbursements")
            if (rnd.nextInt(14) == 0) add(date, 12, Provider.GOJEK, Direction.CREDIT, 4_000L + rnd.nextInt(12) * 1_000L, "Cashback GoPay", "Other Income")
            if (rnd.nextInt(18) == 0) add(date, 15, Provider.SHOPEE, Direction.CREDIT, 25_000L + rnd.nextInt(20) * 10_000L, "Shopee: Pengembalian Dana Pesanan", "Reimbursements")
            if (rnd.nextInt(30) == 0) add(date, 13, Provider.GRAB, Direction.CREDIT, 8_000L + rnd.nextInt(6) * 2_000L, "Grab: Kredit Promo", "Other Income")
            if (rnd.nextInt(90) == 0) add(date, 16, Provider.JAGO, Direction.CREDIT, 250_000L + rnd.nextInt(10) * 50_000L, "Hadiah ulang tahun", "Gifts")
            if (dom == 3 && month in listOf(3, 9)) add(date, 11, Provider.JAGO, Direction.DEBIT, 1_850_000, "Pajak Kendaraan Tahunan", "Taxes & Admin")

            // A trip in June and another over year-end: lodging, rides and meals cost more.
            val onTrip = (month == 6 && dom in 14..18) || (month == 12 && dom in 27..31)
            if (onTrip && dom == (if (month == 6) 14 else 27)) {
                add(date, 10, Provider.JAGO, Direction.DEBIT, 1_450_000L + rnd.nextInt(8) * 50_000L, "Tiket Kereta Lintas Pulau", "Travel & Lodging")
                add(date, 14, Provider.JAGO, Direction.DEBIT, 2_100_000L + rnd.nextInt(10) * 100_000L, "Hotel Pelangi Resort", "Travel & Lodging")
            }

            shops.forEach { shop ->
                if (shop.weekendOnly && !weekend) return@forEach
                if (shop.weekdaysOnly && weekend) return@forEach
                if (rnd.nextDouble() < shop.perDay * (if (onTrip) 1.6 else 1.0)) {
                    val hour = shop.hours.first + rnd.nextInt(shop.hours.last - shop.hours.first + 1)
                    add(date, hour, shop.provider, Direction.DEBIT, roundedRupiah(shop.minRp, shop.maxRp), shop.names[rnd.nextInt(shop.names.size)], shop.category, qris = shop.qris)
                }
            }
            date = date.plusDays(1)
        }
        return Data(rows.sortedBy { it.occurredAt }, labels)
    }

    /** The settled-payment evidence each source's real importer records; the reconciler only counts rows that carry it. */
    private fun walletPayload(provider: Provider, direction: Direction, amountMinor: Long, at: Instant): String {
        fun settled(origin: String, evidence: String) = JSONObject().put("origin", origin).put("paymentEvidence", evidence)
            .put("paymentStatus", "COMPLETED").put("paymentDirection", direction.name).put("paymentCurrency", "IDR")
            .put("paymentAmountMinor", amountMinor).put("paymentTimestamp", at.toString())
        return when {
            provider == Provider.GOJEK -> settled("gopay-order-history", "settled-wallet-history")
            provider == Provider.GRAB -> settled("grab-wallet-api", "settled-charge-history")
            direction == Direction.CREDIT -> settled("shopee-refund-api", "settled-provider-refund")
                .put("sourceRefundStatus", 5L).put("verifiedRefund", true)
            else -> JSONObject().put("origin", "shopee-order-api").put("paymentDetailVerified", true)
                .put("sourceAmountPaid", amountMinor * 1000).put("sourcePaymentTime", at.epochSecond)
        }.toString()
    }
}
