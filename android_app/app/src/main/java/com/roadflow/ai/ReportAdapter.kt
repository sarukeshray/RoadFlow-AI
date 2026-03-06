package com.roadflow.ai

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ReportAdapter — RecyclerView adapter for damage report cards
 * in the Officer Dashboard bottom sheet.
 */
class ReportAdapter(
    private val onVerifyClick: (DamageReport) -> Unit
) : RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    private val reports = mutableListOf<DamageReport>()

    fun submitList(newReports: List<DamageReport>) {
        reports.clear()
        reports.addAll(newReports)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(reports[position])
    }

    override fun getItemCount(): Int = reports.size

    inner class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRhiScore: TextView = itemView.findViewById(R.id.tvRhiScore)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvDamageSummary: TextView = itemView.findViewById(R.id.tvDamageSummary)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val btnVerifyRepair: MaterialButton = itemView.findViewById(R.id.btnVerifyRepair)

        fun bind(report: DamageReport) {
            // RHI Score with color
            tvRhiScore.text = "RHI: ${report.rhiScore}"
            val rhiColor = when {
                report.rhiScore >= 75 -> Color.rgb(40, 167, 69)
                report.rhiScore >= 40 -> Color.rgb(255, 193, 7)
                else -> Color.rgb(220, 53, 69)
            }
            tvRhiScore.setTextColor(rhiColor)

            // Timestamp — relative time
            tvTimestamp.text = getRelativeTime(report.timestamp)

            // Damage summary
            tvDamageSummary.text = report.damageSummary.ifEmpty { "Unknown damage" }

            // Location
            tvLocation.text = String.format(Locale.US, "%.4f°N, %.4f°E", report.lat, report.lon)

            // Verify button
            btnVerifyRepair.setOnClickListener {
                onVerifyClick(report)
            }
        }

        private fun getRelativeTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                diff < TimeUnit.HOURS.toMillis(1) -> {
                    val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
                    "$mins min ago"
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diff)
                    "$hours hr ago"
                }
                else -> {
                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
                }
            }
        }
    }
}
