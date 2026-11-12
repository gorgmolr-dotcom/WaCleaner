package com.personal.wacleaner

import android.Manifest
import android.content.ContentProviderOperation
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

    private fun scanAsync() {
        status.text = "⏳ جارِ الفحص..."
        Thread {
            val onWa = whatsappContactIds()
            var total = 0; var waCount = 0
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID), null, null, null
            )?.use { c ->
                val col = c.getColumnIndex(ContactsContract.Contacts._ID)
                while (c.moveToNext()) { total++; if (c.getString(col) in onWa) waCount++ }
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
            val onWa = whatsappContactIds()
            val list = mutableListOf<String>()
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID), null, null, null
            )?.use { c ->
                val col = c.getColumnIndex(ContactsContract.Contacts._ID)
                while (c.moveToNext()) { val id = c.getString(col); if (id !in onWa) list.add(id) }
            }
            runOnUiThread {
                // قفل أمان: لو ما في أي رقم على واتساب، لا تحذف
                if (onWa.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ تم إيقاف الحذف للحماية")
                        .setMessage("لم يُعثر على أي رقم عليه واتساب على هذا الهاتف، فستُعتبر كل جهات الاتصال 'بدون واتساب' والحذف سيمسح كل شيء!\n\nالحل: افتح واتساب، امنحه إذن جهات الاتصال، اتركه يزامن، ثم ارجع واضغط فحص.")
                        .setPositiveButton("فهمت", null).show()
                    status.text = "تم إيقاف الحذف (0 على واتساب)"
                    return@runOnUiThread
                }
                if (list.isEmpty()) {
                    Toast.makeText(this, "لا يوجد ما يُحذف", Toast.LENGTH_SHORT).show()
                    status.text = "لا يوجد أرقام بدون واتساب"; return@runOnUiThread
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

    // حذف بالدُفعات مع عدّاد تنازلي (يتحمّل مئات الآلاف)
    private fun deleteAsync(list: List<String>) {
        val total = list.size
        status.text = "⏳ جارِ الحذف... المتبقي: $total"
        Thread {
            var deleted = 0; var i = 0; val chunk = 200
            while (i < list.size) {
                val end = minOf(i + chunk, list.size)
                val ops = ArrayList<ContentProviderOperation>()
                for (j in i until end) {
                    ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                        .withSelection("${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(list[j]))
                        .build())
                }
                try {
                    val res = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    for (r in res) deleted += (r.count ?: 0)
                } catch (_: Exception) {
                    for (j in i until end) {
                        try {
                            deleted += contentResolver.delete(
                                ContactsContract.RawContacts.CONTENT_URI,
                                "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(list[j]))
                        } catch (_: Exception) {}
                    }
                }
                i = end
                val remaining = (total - i).coerceAtLeast(0)
                runOnUiThread {
                    noWaNum.text = remaining.toString()
                    status.text = "⏳ جارِ الحذف... المتبقي: $remaining"
                }
            }
            val failed = total - deleted
            runOnUiThread {
                status.text = if (failed > 0)
                    "✅ حُذف $deleted · تعذّر $failed (SIM أو حساب للقراءة فقط)"
                else "✅ تم حذف $deleted رقم بنجاح"
                scanAsync()
            }
        }.start()
    }

    // يكتشف واتساب العادي + واتساب للأعمال
    private fun whatsappContactIds(): Set<String> {
        val ids = mutableSetOf<String>()
        val mimes = arrayOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
        )
        for (mime in mimes) {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID),
                "${ContactsContract.Data.MIMETYPE}=?", arrayOf(mime), null
            )?.use { c ->
                val col = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                while (c.moveToNext()) ids.add(c.getString(col))
            }
        }
        return ids
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), 1)
        }
    }
}
