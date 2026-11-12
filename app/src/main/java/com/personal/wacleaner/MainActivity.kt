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

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        ensurePermissions()

        findViewById<Button>(R.id.scan).setOnClickListener {
            if (!hasPermissions()) { ensurePermissions(); return@setOnClickListener }
            val count = nonWhatsapp().size
            status.text = "يوجد $count رقم بدون واتساب"
        }

        findViewById<Button>(R.id.delete).setOnClickListener {
            if (!hasPermissions()) { ensurePermissions(); return@setOnClickListener }
            val list = nonWhatsapp()
            if (list.isEmpty()) {
                Toast.makeText(this, "لا يوجد ما يُحذف", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("تأكيد")
                .setMessage("سيتم حذف ${list.size} رقم نهائياً. خذ نسخة احتياطية أولاً. متابعة؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("احذف") { _, _ ->
                    var deleted = 0
                    for (id in list) {
                        deleted += contentResolver.delete(
                            ContactsContract.RawContacts.CONTENT_URI,
                            "${ContactsContract.RawContacts.CONTACT_ID}=?",
                            arrayOf(id)
                        )
                    }
                    status.text = "تم حذف ${list.size} رقم"
                }
                .show()
        }
    }

    // كل جهة اتصال عندها واتساب (واتساب يضع علامة داخل دفتر الهاتف)
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

    // كل جهة اتصال ليست على واتساب
    private fun nonWhatsapp(): List<String> {
        val onWa = whatsappContactIds()
        val out = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null, null, null
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
