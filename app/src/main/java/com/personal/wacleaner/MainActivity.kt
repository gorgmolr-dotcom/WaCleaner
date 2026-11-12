package com.personal.wacleaner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var totalNum: TextView
    private lateinit var waNum: TextView
    private lateinit var noWaNum: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        totalNum = findViewById(R.id.totalNum)
        waNum = findViewById(R.id.waNum)
        noWaNum = findViewById(R.id.noWaNum)
        status = findViewById(R.id.status)

        ensurePermissions()

        findViewById<Button>(R.id.scan).setOnClickListener {
            if (!hasPermissions()) { ensurePermissions(); return@setOnClickListener }
            scanAsync()
        }

        findViewById<Button>(R.id.delete).setOnClickListener {
            if (!hasPermissions()) { ensurePermissions(); return@setOnClickListener }
            confirmAndDelete()
        }
    }

    // الفحص في الخلفية حتى لا يتجمد التطبيق
    private fun scanAsync() {
        status.text = "⏳ جارِ الفحص..."
        Thread {
            val onWa = whatsappContactIds()
            var total = 0
            var waCount = 0
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID), null, null, null
            )?.use { c ->
                val col = c.getColumnIndex(ContactsContract.Contacts._ID)
                while (c.moveToNext()) {
                    total++
                    if (c.getString(col) in onWa) waCount++
                }
            }
            val noWaCount = total - waCount
            runOnUiThread {
                totalNum.text = total.toString()
                waNum.text = waCount.toString()
                noWaNum.text = noWaCount.toString()
                status.text = "تم الفحص ✔  اضغط حذف لإزالة الـ $noWaCount رقم"
            }
        }.start()
    }

    private fun confirmAndDelete() {
        status.text = "⏳ جارِ تجهيز القائمة..."
        Thread {
            val list = nonWhatsapp()
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "لا يوجد ما يُحذف", Toast.LENGTH_SHORT).show()
                    status.text = "لا يوجد أرقام بدون واتساب"
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("تأكيد الحذف")
                    .setMessage("سيتم حذف ${list.size} رقم نهائياً.\nتأكد أنك أخذت نسخة احتياطية. متابعة؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("احذف") { _, _ -> deleteAsync(list) }
                    .show()
            }
        }.start()
    }

    // الحذف في الخلفية مع عدّ ما تم حذفه فعلياً
    private fun deleteAsync(list: List<String>) {
        status.text = "⏳ جارِ الحذف..."
        Thread {
            var deleted = 0
            for (id in list) {
                try {
                    val n = contentResolver.delete(
                        ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts.CONTACT_ID}=?",
                        arrayOf(id)
                    )
                    if (n > 0) deleted++
                } catch (_: Exception) { }
            }
            val failed = list.size - deleted
            runOnUiThread {
                status.text = if (failed > 0)
                    "✅ حُذف $deleted · تعذّر حذف $failed (أرقام SIM أو حساب للقراءة فقط)"
                else
                    "✅ تم حذف $deleted رقم بنجاح"
                scanAsync()
            }
        }.start()
    }

    private fun whatsappContactIds(): Set<String> {
        val ids = mutableSetOf<String>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.profile"),
            null
        )?.use { c ->
            val col = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            while (c.moveToNext()) ids.add(c.getString(col))
        }
        return ids
    }

    private fun nonWhatsapp(): List<String> {
        val onWa = whatsappContactIds()
        val out = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID), null, null, null
        )?.use { c ->
            val col = c.getColumnIndex(ContactsContract.Contacts._ID)
            while (c.moveToNext()) {
                val id = c.getString(col)
                if (id !in onWa) out.add(id)
            }
        }
        return out
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                1
            )
        }
    }
}
