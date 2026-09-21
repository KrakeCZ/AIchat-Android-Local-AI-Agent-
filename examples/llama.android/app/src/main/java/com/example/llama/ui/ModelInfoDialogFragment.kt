package com.example.llama.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.llama.R
import com.example.llama.data.ModelDetailState
import java.text.DecimalFormat

class ModelInfoDialogFragment : DialogFragment() {

    private var modelDetail: ModelDetailState? = null

    companion object {
        private const val ARG_DETAIL = "arg_detail"

        fun newInstance(detail: ModelDetailState): ModelInfoDialogFragment {
            val fragment = ModelInfoDialogFragment()
            val args = Bundle()
            args.putParcelable(ARG_DETAIL, detail)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Min SDK is 33, so we can directly use the type-safe getParcelable API
        arguments?.let {
            modelDetail = it.getParcelable(ARG_DETAIL, ModelDetailState::class.java)
        }
        // Transparent background so CardView corners show
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate layout with transparent container logic handled by Dialog styling or root layout
        return inflater.inflate(R.layout.dialog_model_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Close Button
        view.findViewById<View>(R.id.btnClose).setOnClickListener {
            dismiss()
        }

        val container = view.findViewById<LinearLayout>(R.id.infoContainer)
        val tvTemplate = view.findViewById<TextView>(R.id.tvTemplate)

        modelDetail?.let { detail ->
            // Populate Rows dynamically
            addRow(container, getString(R.string.lbl_label), detail.name, copyable = true) // Using generic "Label" placeholder or proper string? Let's use hardcoded for key names as they are technical terms mostly.
            // Wait, previous code used "Model", "File Path". Let's stick to English technical terms or use strings.
            // For now, I will keep hardcoded technical labels as requested by "Master IT" (consistency).
            // Actually, best practice is strings.xml but "Model", "Architecture" are universal.

            addRow(container, "Model", detail.name, copyable = true)
            addRow(container, "File Path", detail.filePath, copyable = true, truncate = true)
            addRow(container, "Architecture", detail.architecture)
            addRow(container, "Context Size", "${formatNumber(detail.contextSize)} tokens")
            addRow(container, "Model Size", detail.modelSize)
            if (detail.parameters.isNotEmpty() && detail.parameters != "N/A") {
                addRow(container, "Parameters", detail.parameters)
            }
            addRow(container, "Embedding Size", "${detail.embeddingSize}")
            addRow(container, "Vocabulary Size", "${formatNumber(detail.vocabSize)} tokens")
            if (detail.quantization.isNotEmpty()) {
                addRow(container, "Build Info / Quant", detail.quantization)
            }

            // Template
            tvTemplate.text = detail.chatTemplate.ifEmpty { "No template found in metadata." }
        }
    }

    override fun onStart() {
        super.onStart()
        // Make the dialog width 95% of screen
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.90).toInt()
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun addRow(
        parent: LinearLayout,
        label: String,
        value: String,
        copyable: Boolean = false,
        truncate: Boolean = false
    ) {
        val rowView = layoutInflater.inflate(R.layout.item_info_row, parent, false)

        val tvLabel = rowView.findViewById<TextView>(R.id.tvLabel)
        val tvValue = rowView.findViewById<TextView>(R.id.tvValue)
        val btnCopy = rowView.findViewById<ImageView>(R.id.btnCopy)

        tvLabel.text = label
        tvValue.text = value

        if (truncate) {
            tvValue.maxLines = 1
            tvValue.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }

        if (copyable) {
            btnCopy.visibility = View.VISIBLE
            btnCopy.setOnClickListener {
                copyToClipboard(value)
            }
        } else {
            btnCopy.visibility = View.GONE
        }

        val insertIndex = (parent.childCount - 4).coerceAtLeast(0)
        parent.addView(rowView, insertIndex)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Model Info", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, getString(R.string.msg_copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun formatNumber(value: Long): String {
        val formatter = DecimalFormat("#,###")
        return formatter.format(value).replace(",", " ")
    }
}
