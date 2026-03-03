package com.abuzahra.child.utils

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object FirestoreHelper {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private fun getDeviceId(): String {
        return auth.currentUser?.uid ?: "unknown_device"
    }

    suspend fun updateChildStatus(data: Map<String, Any>) {
        val deviceId = getDeviceId()
        if (deviceId == "unknown_device") return

        try {
            db.collection("children").document(deviceId)
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e("FirestoreHelper", "Error updating status", e)
        }
    }

    suspend fun uploadLog(collection: String, data: Map<String, Any>) {
        val deviceId = getDeviceId()
        if (deviceId == "unknown_device") return

        try {
            db.collection("children").document(deviceId)
                .collection(collection)
                .add(data)
                .await()
        } catch (e: Exception) {
            Log.e("FirestoreHelper", "Error uploading log", e)
        }
    }
}
