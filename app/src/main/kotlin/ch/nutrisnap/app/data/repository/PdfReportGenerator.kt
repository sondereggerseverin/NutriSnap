package ch.nutrisnap.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ch.nutrisnap.app.data.db.NutriDatabase
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================
// FEATURE 6: PDF-Report (Wochen-/Monatsbericht)
//
// Dependency in app/build.gradle.kts bereits ergänzt:
//   implementation("com.itextpdf:itext7-core:7.2.5") { exclude(group = "org.bouncycastle") }
// FileProvider in AndroidManifest.xml + res/xml/file_paths.xml bereits ergänzt.
//
// Integration:
//  1. PdfReportGenerator(context, db) dort instanziieren, wo Export ausgelöst wird
//     (z.B. neuer Export-Screen/ViewModel, manuell wie HomeViewModel — kein Hilt im Projekt).
//  2. generateWeeklyReport()/generateMonthlyReport() aus einem Coroutine-Scope aufrufen,
//     danach shareReport(file).
//
// Abweichungen vom ursprünglichen Entwurf (Original referenzierte nicht existierende APIs):
//  - Tagesdaten kommen aus DiaryRepository.getSummaryBetween() (liefert kcal+Makros pro Tag
//    in einem Rutsch) statt aus StatsRepository.getDailyKcalHistory()/getDailyMacrosHistory(),
//    die es nicht gibt.
//  - Kalorienziel ist das statische Tagesziel aus dem Profil (profile.dailyCalorieGoal), nicht
//    das tagesaktuelle adaptive TDEE-Ziel — für einen Bericht über vergangene Wochen ist ein
//    fixer Vergleichswert nachvollziehbarer als der heutige adaptive Wert, der sich täglich
//    ändert. AdaptiveTdeeCalculator ist zudem ein object (Singleton), kein injizierbarer Typ.
//  - Die "TDEE"-Spalte der Tabelle war im Entwurf ohnehin nie befüllt (immer "–") und wurde
//    ersatzlos entfernt statt als Attrappe stehen zu lassen.
//  - Kein Nutzername im Header, da UserProfile kein Namensfeld hat.
// ============================================================

class PdfReportGenerator(
    private val context: Context,
    db: NutriDatabase
) {
    private val diaryRepository = DiaryRepository(db)
    private val weightRepository = WeightRepository(db)
    private val profileRepository = UserProfileRepository(db)

    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)

    suspend fun generateWeeklyReport(): File = createReport(weeksBack = 1, label = "Wochenreport")
    suspend fun generateMonthlyReport(): File = createReport(weeksBack = 4, label = "Monatsbericht")

    private suspend fun createReport(weeksBack: Int, label: String): File {
        val days = weeksBack * 7
        val today = LocalDate.now()
        val from = today.minusDays(days.toLong())

        val dailySummaries = diaryRepository.getSummaryBetween(from, today).first()
            .associateBy { LocalDate.parse(it.dateStr) }
        val weightByDate = weightRepository.getRecent(days).first()
            .associate { LocalDate.parse(it.dateStr) to it.weightKg }
        val profile = profileRepository.get().first()
        val goalKcal = profile.dailyCalorieGoal

        val startDateStr = from.format(dateFmt)
        val endDateStr = today.format(dateFmt)

        val fileName = "NutriSnap_${label}_${startDateStr}_${endDateStr}.pdf".replace(".", "-")
        val file = File(context.getExternalFilesDir(null), fileName)

        val pdfDoc = PdfDocument(PdfWriter(file))
        val doc = Document(pdfDoc, PageSize.A4)
        doc.setMargins(36f, 36f, 36f, 36f)

        // Header
        doc.add(Paragraph("NutriSnap – $label").setFontSize(20f).setBold().setTextAlignment(TextAlignment.CENTER))
        doc.add(Paragraph("$startDateStr – $endDateStr").setFontSize(11f)
            .setTextAlignment(TextAlignment.CENTER).setFontColor(ColorConstants.GRAY))
        doc.add(Paragraph(" "))

        // Zusammenfassung
        val avgKcal = dailySummaries.values.map { it.calories.toDouble() }.average0()
        val avgProtein = dailySummaries.values.map { it.protein.toDouble() }.average0()
        val avgCarbs = dailySummaries.values.map { it.carbs.toDouble() }.average0()
        val avgFat = dailySummaries.values.map { it.fat.toDouble() }.average0()

        doc.add(Paragraph("Zusammenfassung").setFontSize(14f).setBold())
        val sumTable = Table(UnitValue.createPercentArray(floatArrayOf(50f, 50f))).useAllAvailableWidth()
        listOf(
            "Ø Kalorien/Tag" to "${avgKcal.toInt()} kcal",
            "Kalorienziel" to "$goalKcal kcal",
            "Ø Protein/Tag" to "${avgProtein.toInt()} g",
            "Ø Kohlenhydrate/Tag" to "${avgCarbs.toInt()} g",
            "Ø Fett/Tag" to "${avgFat.toInt()} g"
        ).forEach { (k, v) ->
            sumTable.addCell(Cell().add(Paragraph("$k: $v").setFontSize(10f)).setBorder(null))
        }
        doc.add(sumTable)
        doc.add(Paragraph(" "))

        // Tagesverlauf-Tabelle
        doc.add(Paragraph("Tagesverlauf").setFontSize(14f).setBold())
        val table = Table(UnitValue.createPercentArray(floatArrayOf(20f, 16f, 16f, 16f, 14f, 18f)))
            .useAllAvailableWidth()
        listOf("Datum", "kcal", "Protein", "Carbs", "Fett", "Gewicht").forEach {
            table.addHeaderCell(Cell().add(Paragraph(it).setBold().setFontSize(8f)))
        }

        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .forEach { date ->
                val summary = dailySummaries[date]
                val weight = weightByDate[date]
                val overGoal = (summary?.calories ?: 0f) > goalKcal * 1.1f
                val rowColor = if (overGoal) DeviceRgb(255, 230, 230) else null

                listOf(
                    date.format(dateFmt),
                    summary?.calories?.toInt()?.toString() ?: "–",
                    summary?.let { "${it.protein.toInt()}g" } ?: "–",
                    summary?.let { "${it.carbs.toInt()}g" } ?: "–",
                    summary?.let { "${it.fat.toInt()}g" } ?: "–",
                    weight?.let { String.format("%.1f kg", it) } ?: "–"
                ).forEach { text ->
                    val cell = Cell().add(Paragraph(text).setFontSize(8f))
                    if (rowColor != null) cell.setBackgroundColor(rowColor)
                    table.addCell(cell)
                }
            }
        doc.add(table)

        doc.add(Paragraph("\nErstellt mit NutriSnap • ${today.format(dateFmt)}")
            .setFontSize(8f).setFontColor(ColorConstants.GRAY).setTextAlignment(TextAlignment.RIGHT))
        doc.close()
        return file
    }

    fun shareReport(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Report teilen"))
    }
}

/** average(), aber 0.0 statt Absturz auf einer leeren Liste (kein Diary-Eintrag im Zeitraum). */
private fun List<Double>.average0(): Double = if (isEmpty()) 0.0 else average()
