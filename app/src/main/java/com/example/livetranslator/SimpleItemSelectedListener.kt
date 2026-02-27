package com.example.livetranslator

import android.view.View
import android.widget.AdapterView

/** Convenience wrapper so callers only need to implement onItemSelected. */
class SimpleItemSelectedListener(private val onSelected: () -> Unit) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected()
    }
    override fun onNothingSelected(parent: AdapterView<*>?) {}
}
