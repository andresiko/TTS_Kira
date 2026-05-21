package com.kira.tts

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogActivity : AppCompatActivity(), LogStore.Listener {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnPause: Button
    private lateinit var btnClear: Button
    private lateinit var adapter: LogAdapter
    private lateinit var layoutManager: LinearLayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        recycler = findViewById(R.id.recyclerLog)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPause = findViewById(R.id.btnPause)
        btnClear = findViewById(R.id.btnClear)

        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.layoutManager = layoutManager
        adapter = LogAdapter()
        recycler.adapter = adapter
        recycler.itemAnimator = null

        btnPause.setOnClickListener {
            LogStore.paused = !LogStore.paused
            btnPause.text = getString(if (LogStore.paused) R.string.log_resume else R.string.log_pause)
        }
        btnClear.setOnClickListener { LogStore.clear() }
    }

    override fun onStart() {
        super.onStart()
        LogStore.setListener(this)
    }

    override fun onStop() {
        super.onStop()
        LogStore.setListener(null)
    }

    override fun onEntriesChanged() {
        val snapshot = LogStore.snapshot()
        tvEmpty.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (snapshot.isEmpty()) View.GONE else View.VISIBLE
        val atBottom = layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 2
        adapter.submit(snapshot)
        if (atBottom && snapshot.isNotEmpty()) {
            recycler.scrollToPosition(snapshot.size - 1)
        }
    }
}
