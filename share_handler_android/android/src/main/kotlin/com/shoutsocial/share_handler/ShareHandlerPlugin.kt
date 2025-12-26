package com.shoutsocial.share_handler

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap

import androidx.annotation.NonNull
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection

private const val kEventsChannel = "com.shoutsocial.share_handler/sharedMediaStream"
private const val PREFS_NAME = "share_handler_prefs"
private const val KEY_HANDLED_INTENT_ID = "handled_intent_id"
private const val KEY_HANDLED_INTENT_TIME = "handled_intent_time"
// Expiry time of 60 seconds: long enough to cover process restart, 
// but short enough to allow users to quickly re-share the same content
private const val INTENT_EXPIRY_MS = 60 * 1000L

/** ShareHandlerPlugin */
class ShareHandlerPlugin : FlutterPlugin, Messages.ShareHandlerApi, EventChannel.StreamHandler, ActivityAware,
  PluginRegistry.NewIntentListener {
  private var initialMedia: Messages.SharedMedia? = null
  private var eventChannel: EventChannel? = null
  private var eventSink: EventChannel.EventSink? = null

  private var binding: ActivityPluginBinding? = null
  private lateinit var applicationContext: Context

  /**
   * Generates a unique identifier for a share Intent based on its content.
   * Returns null if the Intent doesn't contain share-related data.
   */
  private fun getIntentId(intent: Intent): String? {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
      return null
    }

    val parts = mutableListOf<String>()

    // Add action
    parts.add(intent.action ?: "")

    // Add URIs from EXTRA_STREAM
    when (intent.action) {
      Intent.ACTION_SEND -> {
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
          parts.add(it.toString())
        }
      }
      Intent.ACTION_SEND_MULTIPLE -> {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach {
          parts.add(it.toString())
        }
      }
    }

    // Add text content
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
      parts.add(it)
    }

    // Add conversation identifier
    (intent.getStringExtra("android.intent.extra.shortcut.ID")
      ?: intent.getStringExtra("conversationIdentifier"))?.let {
      parts.add(it)
    }

    // Add MIME type
    intent.type?.let {
      parts.add(it)
    }

    // If no meaningful content, return null
    if (parts.size <= 1) {
      return null
    }

    // Generate a hash of the content
    return parts.joinToString("|").hashCode().toString()
  }

  /**
   * Checks if the given Intent has already been handled (persisted across process death).
   * Uses a time-based expiration to allow re-sharing the same content after a short period.
   */
  private fun isIntentAlreadyHandled(intent: Intent): Boolean {
    val intentId = getIntentId(intent) ?: return false
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val handledIntentId = prefs.getString(KEY_HANDLED_INTENT_ID, null)
    val handledTime = prefs.getLong(KEY_HANDLED_INTENT_TIME, 0L)
    
    // Only consider as duplicate if ID matches AND within expiry window
    if (intentId == handledIntentId) {
      val elapsed = System.currentTimeMillis() - handledTime
      if (elapsed < INTENT_EXPIRY_MS) {
        return true
      }
      // Expired, clear the old record
      clearHandledIntent()
    }
    return false
  }

  /**
   * Marks the given Intent as handled in persistent storage with timestamp.
   */
  private fun markIntentAsHandled(intent: Intent) {
    val intentId = getIntentId(intent) ?: return
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(KEY_HANDLED_INTENT_ID, intentId)
      .putLong(KEY_HANDLED_INTENT_TIME, System.currentTimeMillis())
      .apply()
    Log.d("ShareHandlerPlugin", "Marked intent as handled: $intentId")
  }

  /**
   * Clears the handled Intent record from persistent storage.
   */
  private fun clearHandledIntent() {
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .remove(KEY_HANDLED_INTENT_ID)
      .remove(KEY_HANDLED_INTENT_TIME)
      .apply()
  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    applicationContext = flutterPluginBinding.applicationContext

    val messenger = flutterPluginBinding.binaryMessenger
    Messages.ShareHandlerApi.setup(messenger, this)

    eventChannel = EventChannel(messenger, kEventsChannel)
    eventChannel?.setStreamHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    Messages.ShareHandlerApi.setup(binding.binaryMessenger, null)
  }

//  override fun getInitialSharedMedia(result: Result<SharedMedia>?) {
//    result?.let { _result -> {
//      initialMedia?.let { _media -> _result.success(_media) }
//    } }
//  }

//  override fun recordSentMessage(media: SharedMedia) {
//    val packageName = applicationContext.packageName
//    val shortcutTarget = "$packageName.dynamic_share_target"
//    val shortcutBuilder = ShortcutInfoCompat.Builder(applicationContext, media.conversationIdentifier ?: "").setShortLabel(media.speakableGroupName ?: "Unknown")
//      .setIsConversation()
//      .setCategories(setOf(shortcutTarget))
//      .setIntent(Intent(Intent.ACTION_DEFAULT))
//      .setLongLived(true)
//
//    val personBuilder = Person.Builder()
//      .setKey(media.conversationIdentifier)
//      .setName(media.speakableGroupName)
//
//    media.imageFilePath?.let {
//      val bitmap = BitmapFactory.decodeFile(it)
//      val icon = IconCompat.createWithAdaptiveBitmap(bitmap)
//      shortcutBuilder.setIcon(icon)
//      personBuilder.setIcon(icon)
//    }
//
//    val person = personBuilder.build()
//    shortcutBuilder.setPerson(person)
//
//    val shortcut = shortcutBuilder.build()
//
//    ShortcutManagerCompat.addDynamicShortcuts(applicationContext, listOf(shortcut))
//  }

  override fun getInitialSharedMedia(result: Messages.Result<Messages.SharedMedia>?) {
    result?.success(initialMedia)
  }

  override fun recordSentMessage(media: Messages.SharedMedia) {
    val packageName = applicationContext.packageName
    val intent = Intent(applicationContext, Class.forName("$packageName.MainActivity")).apply {
      action = Intent.ACTION_SEND
      putExtra("conversationIdentifier", media.conversationIdentifier)
    }
    val shortcutTarget = "$packageName.dynamic_share_target"
    val shortcutBuilder = ShortcutInfoCompat.Builder(applicationContext, media.conversationIdentifier ?: "")
      .setShortLabel(media.speakableGroupName ?: "Unknown")
      .setIsConversation()
      .setCategories(setOf(shortcutTarget))
      .setIntent(intent)
      .setLongLived(true)

    val personBuilder = Person.Builder()
      .setKey(media.conversationIdentifier)
      .setName(media.speakableGroupName)

    media.imageFilePath?.let {
      val bitmap = BitmapFactory.decodeFile(it)
      val icon = IconCompat.createWithAdaptiveBitmap(bitmap)
      shortcutBuilder.setIcon(icon)
      personBuilder.setIcon(icon)
    }

    val person = personBuilder.build()
    shortcutBuilder.setPerson(person)

    val shortcut = shortcutBuilder.build()

    ShortcutManagerCompat.addDynamicShortcuts(applicationContext, listOf(shortcut))
  }

  override fun resetInitialSharedMedia() {
    initialMedia = null
    binding?.activity?.let { activity ->
      val intent = activity.intent
      if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
        // Mark this intent as handled in persistent storage before modifying it
        markIntentAsHandled(intent)
        
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra("conversationIdentifier")
        intent.type = null
        activity.intent = intent
      }
    }
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.binding = binding
    binding.addOnNewIntentListener(this)
    val intent = binding.activity.intent
    val flags: Int = intent.flags
    if ((flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
      // The activity was launched from history
      Log.w("ShareHandlerPlugin", "Handle skip: The activity was launched from history")
    } else if (isIntentAlreadyHandled(intent)) {
      // The intent was already handled before process death
      Log.w("ShareHandlerPlugin", "Handle skip: Intent already handled before process death")
    } else {
      handleIntent(intent, true)
      markIntentAsHandled(intent)
    }
  }

  override fun onDetachedFromActivityForConfigChanges() {
    binding?.removeOnNewIntentListener(this)
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    this.binding = binding
    binding.addOnNewIntentListener(this)
  }

  override fun onDetachedFromActivity() {
    binding?.removeOnNewIntentListener(this)
  }

  override fun onNewIntent(intent: Intent): Boolean {
    // Clear any previous handled intent marker for hot-start shares
    // This ensures new shares are always processed even if content is identical
    clearHandledIntent()
    handleIntent(intent, false)
    markIntentAsHandled(intent)
    return false
  }

  private fun handleIntent(intent: Intent, initial: Boolean) {
    val attachments: List<Messages.SharedAttachment>? = try {
      attachmentsFromIntent(intent)
    } catch (e: Exception) {
      Log.e("ShareHandlerPlugin", "Error parsing attachments", e)
      null
    }

    val text: String? = when (intent.action) {
      Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> intent.getStringExtra(Intent.EXTRA_TEXT)
      else -> null
    }

    val conversationIdentifier = intent.getStringExtra("android.intent.extra.shortcut.ID")
      ?: intent.getStringExtra("conversationIdentifier")

    if (attachments != null || text != null || conversationIdentifier != null) {
      val mediaBuilder = Messages.SharedMedia.Builder()
      attachments?.let { mediaBuilder.setAttachments(it) }
      text?.let { mediaBuilder.setContent(it) }
      conversationIdentifier?.let { mediaBuilder.setConversationIdentifier(it) }
      val media = mediaBuilder.build()

      if (initial) {
        synchronized(this) {
          initialMedia = media
        }
      }

      if (eventSink != null) {
        eventSink?.success(media.toMap())
      } else {
        Log.w("ShareHandlerPlugin", "EventSink is not available")
      }
    }
  }

  private fun attachmentsFromIntent(intent: Intent?): List<Messages.SharedAttachment>? {
    if (intent == null) return null
    return when (intent.action) {
      Intent.ACTION_SEND -> {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return null
        return listOf(attachmentForUri(uri)).mapNotNull { it }
      }

      Intent.ACTION_SEND_MULTIPLE -> {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        val value = uris?.mapNotNull { uri ->
          attachmentForUri(uri)
        }?.toList()
        return value
      }

      else -> null
    }
  }

  private fun attachmentForUri(uri: Uri): Messages.SharedAttachment? {
    val contentResolver = applicationContext.contentResolver

    // Obtain the MIME type of the URI
    val mimeType = contentResolver.getType(uri)

    // Get the absolute path from the URI
    val path = FileDirectory.getAbsolutePath(applicationContext, uri) ?: return null

    val file = File(path)

    // Check if the file name has an extension
    if (file.extension.isNotEmpty()) {
      // File has an extension; use it directly
      val type = getAttachmentType(mimeType)
      return Messages.SharedAttachment.Builder()
        .setPath(file.absolutePath)
        .setType(type)
        .build()
    } else {
      // File does not have an extension; copy it to cache with the correct extension

      // Obtain the file name from the URI, including extension
      val fileName = getFileNameFromUri(contentResolver, uri, mimeType) ?: return null

      // Create a new file in the cache directory with the correct file name
      val newFile = File(applicationContext.cacheDir, fileName)

      // Copy the contents from the URI to the new file
      val success = copyFile(contentResolver, uri, newFile)
      if (!success) {
        return null
      }

      // Determine the attachment type using the MIME type
      val type = getAttachmentType(mimeType)

      // Return the attachment with the path to the copied file
      return Messages.SharedAttachment.Builder()
        .setPath(newFile.absolutePath)
        .setType(type)
        .build()
    }
  }

  // Function to get the file name from the URI
  private fun getFileNameFromUri(contentResolver: ContentResolver, uri: Uri, mimeType: String?): String? {
    var fileName: String? = null
    val cursor = contentResolver.query(uri, null, null, null, null)
    cursor?.use { c ->
      if (c.moveToFirst()) {
        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          fileName = c.getString(nameIndex)
        }
      }
    }

    // If the file name couldn't be obtained, generate one
    if (fileName == null) {
      fileName = "file_${System.currentTimeMillis()}"
      // Add extension if possible
      mimeType?.let {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        if (extension != null) {
          fileName += ".$extension"
        }
      }
    }

    return fileName
  }

  // Function to copy the file content from the URI to the destination file
  private fun copyFile(contentResolver: ContentResolver, uri: Uri, destinationFile: File): Boolean {
    return try {
      contentResolver.openInputStream(uri)?.use { inputStream ->
        FileOutputStream(destinationFile).use { outputStream ->
          val buffer = ByteArray(8 * 1024) // 8KB buffer
          var bytesRead: Int
          while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
          }
        }
      }
      true
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  // Function to determine the attachment type using the MIME type
  private fun getAttachmentType(mimeType: String?): Messages.SharedAttachmentType {
    return when {
      mimeType?.startsWith("image") == true -> Messages.SharedAttachmentType.image
      mimeType?.startsWith("video") == true -> Messages.SharedAttachmentType.video
      mimeType?.startsWith("audio") == true -> Messages.SharedAttachmentType.audio
      else -> Messages.SharedAttachmentType.file
    }
  }
}
