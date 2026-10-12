@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.modelatlas.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberUpdatedState
import dev.modelatlas.app.data.VisionImagePayload
import dev.modelatlas.core.multimodal.ImageUrlPart
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.modelatlas.app.AtlasUiState
import dev.modelatlas.app.ChatMessage
import dev.modelatlas.app.ConnectionItem
import dev.modelatlas.app.R
import dev.modelatlas.app.SampleItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PLAY-001/002/003/005：只組裝畫面與既有 callback；草稿不寫入 Bundle 或資料庫。 */
@Composable
internal fun PlaygroundTab(
  state: AtlasUiState,
  onSelect: (String) -> Unit,
  onSend: (String) -> Unit,
  onSendVision: (String, ImageUrlPart) -> Unit,
  canAttachVision: Boolean,
  selectedGroup: String?,
  onCancel: () -> Unit,
  onGotoProviders: () -> Unit,
  showConnections: Boolean,
  onShowConnections: (Boolean) -> Unit,
) {
  var showSamples by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun openConnections() {
    focusManager.clearFocus()
    keyboard?.hide()
    onShowConnections(true)
  }
  val selectedConnection = remember(state.connections, state.selectedId) {
    state.connections.firstOrNull { it.id == state.selectedId }
  }
  val lastUser = remember(state.messages) { state.messages.lastOrNull { it.role == "user" } }
  val lastMessageId = state.messages.lastOrNull()?.id
  val reversedMessages = remember(state.messages) { state.messages.asReversed() }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val showLatest by remember { derivedStateOf { listState.canScrollBackward } }
  val canSend = selectedConnection != null && selectedConnection.enabled && !state.saving

  // 最新訊息錨定底端；只有新一輪／切換連線才捲動，不逐 token 啟動捲動動畫。
  LaunchedEffect(state.selectedId, lastUser?.id) {
    if (lastUser != null) listState.scrollToItem(0)
  }

  BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val compact = maxHeight < 360.dp
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (state.messages.isEmpty() && state.connectionsLoading) {
          CircularProgressIndicator(Modifier.size(AtlasSpace.icon).align(Alignment.Center))
        } else if (state.messages.isEmpty()) {
          Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            AtlasEmptyStateCard(
              title = stringResource(
                if (state.connections.isEmpty()) R.string.connection_none else R.string.playground_empty_title,
              ),
              description = stringResource(
                if (state.connections.isEmpty()) R.string.playground_connect_hint else R.string.chat_empty,
              ),
              icon = Icons.AutoMirrored.Filled.Send,
              compact = compact,
              action = {
                OutlinedButton(
                  onClick = {
                    if (state.connections.isEmpty()) onGotoProviders() else openConnections()
                  },
                  modifier = Modifier.heightIn(min = AtlasSpace.touch),
                ) {
                  Text(stringResource(
                    if (state.connections.isEmpty()) R.string.action_goto_providers else R.string.playground_switch_model,
                  ))
                }
              },
            )
          }
        } else {
          LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AtlasSpace.lg, vertical = AtlasSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AtlasSpace.lg, Alignment.Bottom),
          ) {
            items(reversedMessages, key = { it.id }, contentType = { it.role }) { message ->
              ChatBubble(
                message = message,
                streaming = state.busy && message.id == lastMessageId && message.role == "assistant",
                canRetry = !state.busy && lastUser != null && lastUser.attachmentName == null && message.id == lastMessageId,
                retryEnabled = canSend,
                // 重試仍經正常 send；不改歷史、不自動重試，也不消耗使用者未送出的草稿。
                onRetry = { lastUser?.let { onSend(it.text) } },
              )
            }
          }
          if (showLatest) {
            Surface(
              modifier = Modifier.align(Alignment.BottomCenter).padding(AtlasSpace.sm),
              shape = AtlasRadius.pill,
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              TextButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier.heightIn(min = AtlasSpace.touch),
              ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                Spacer(Modifier.width(AtlasSpace.xs))
                Text(stringResource(R.string.playground_latest))
              }
            }
          }
        }
      }
      PlaygroundComposer(
        selectedConnection = selectedConnection,
        busy = state.busy,
        canSend = canSend,
        acceptedMessage = lastUser,
        compact = compact,
        onSend = onSend,
        onSendVision = onSendVision,
        canAttachVision = canAttachVision,
        scopeKey = selectedGroup,
        onCancel = onCancel,
        onSelect = ::openConnections,
        onSamples = { focusManager.clearFocus(); keyboard?.hide(); showSamples = true },
      )
    }
  }
  if (showConnections) {
    PlaygroundConnectionSheet(
      connections = state.connections,
      connectionsLoading = state.connectionsLoading,
      selectedId = state.selectedId,
      onSelect = { id -> onSelect(id); onShowConnections(false) },
      onGotoProviders = { onShowConnections(false); onGotoProviders() },
      onDismiss = { onShowConnections(false) },
    )
  }
  if (showSamples) {
    PlaygroundSamplesSheet(samples = state.samples, onDismiss = { showSamples = false })
  }
}

/** Preview is ephemeral Compose state; only bounded and signature-checked data URIs reach the VM. */
private data class PreparedVisionAttachment(
  val image: ImageUrlPart,
  val thumbnail: ImageBitmap,
)

private suspend fun prepareVisionAttachment(context: Context, uri: Uri): PreparedVisionAttachment =
  withContext(Dispatchers.IO) {
    require(uri.scheme == "content") { "Photo picker must provide content URI" }
    val resolver = context.contentResolver
    val filename = runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else {
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index < 0) null else cursor.getString(index)
        }
      }
    }.getOrNull()
    val bytes = ByteArrayOutputStream().use { output ->
      val stream = resolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Photo picker returned no content")
      stream.use { input ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          if (read == 0) continue
          total += read
          require(total <= VisionImagePayload.MAX_IMAGE_BYTES) { "Image exceeds 4 MiB" }
          output.write(buffer, 0, read)
        }
      }
      output.toByteArray()
    }
    val image = VisionImagePayload.prepare(bytes, filename)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) {
      "Image has invalid dimensions"
    }
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
      ?: throw IllegalArgumentException("Image decoder rejected content")
    PreparedVisionAttachment(image, preview.asImageBitmap())
  }

@Composable
private fun PlaygroundComposer(
  selectedConnection: ConnectionItem?,
  busy: Boolean,
  canSend: Boolean,
  acceptedMessage: ChatMessage?,
  compact: Boolean,
  onSend: (String) -> Unit,
  onSendVision: (String, ImageUrlPart) -> Unit,
  canAttachVision: Boolean,
  scopeKey: String?,
  onCancel: () -> Unit,
  onSelect: () -> Unit,
  onSamples: () -> Unit,
) {
  var prompt by remember { mutableStateOf("") }
  var submittedPrompt by remember(selectedConnection?.id) { mutableStateOf<String?>(null) }
  var submittedAfterId by remember(selectedConnection?.id) { mutableStateOf<String?>(null) }
  var submittedAttachmentName by remember(selectedConnection?.id, scopeKey) { mutableStateOf<String?>(null) }
  var pendingImage by remember(selectedConnection?.id, scopeKey) { mutableStateOf<PreparedVisionAttachment?>(null) }
  var imagePreparing by remember(selectedConnection?.id, scopeKey) { mutableStateOf(false) }
  var imageError by remember(selectedConnection?.id, scopeKey) { mutableStateOf(false) }
  val context = LocalContext.current
  val launchScope = rememberCoroutineScope()
  val currentConnectionId by rememberUpdatedState(selectedConnection?.id)
  val currentScopeKey by rememberUpdatedState(scopeKey)
  var pickerConnectionId by remember { mutableStateOf<String?>(null) }
  var pickerGroup by remember { mutableStateOf<String?>(null) }
  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null && pickerConnectionId == currentConnectionId && pickerGroup == currentScopeKey) {
      val expectedId = currentConnectionId
      val expectedGroup = currentScopeKey
      launchScope.launch {
        imagePreparing = true
        imageError = false
        try {
          val prepared = prepareVisionAttachment(context, uri)
          if (expectedId == currentConnectionId && expectedGroup == currentScopeKey) {
            pendingImage = prepared
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (_: Exception) {
          if (expectedId == currentConnectionId && expectedGroup == currentScopeKey) imageError = true
        } finally {
          if (expectedId == currentConnectionId && expectedGroup == currentScopeKey) imagePreparing = false
        }
      }
    }
  }
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  // send 可能在前置檢查即拒絕；看到 VM 接受的新 user message 才清空相符草稿。
  LaunchedEffect(acceptedMessage?.id) {
    val submitted = submittedPrompt
    if (submitted != null && acceptedMessage?.id != submittedAfterId &&
        acceptedMessage?.text == submitted.trim() &&
        acceptedMessage?.attachmentName == submittedAttachmentName) {
      if (prompt == submitted) {
        prompt = ""
        pendingImage = null
        focusManager.clearFocus()
        keyboard?.hide()
      }
      submittedPrompt = null
      submittedAttachmentName = null
    }
  }
  val switchLabel = stringResource(R.string.playground_switch_model)
  val selectedContext = selectedConnection?.let { "${it.modelId} · ${it.name} · ${it.credentialLabel}" }
    ?: stringResource(R.string.connection_none)
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = AtlasSpace.md, vertical = AtlasSpace.sm),
    shape = AtlasRadius.card,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.padding(AtlasSpace.sm)) {
      Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
        val attachLabel = stringResource(R.string.playground_attach_image)
        IconButton(
          onClick = {
            pickerConnectionId = selectedConnection?.id
            pickerGroup = scopeKey
            imagePicker.launch("image/*")
          },
          enabled = canSend && canAttachVision && !busy && !imagePreparing && pendingImage == null,
          modifier = Modifier.size(AtlasSpace.touch).semantics { contentDescription = attachLabel },
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
        }
        TextField(
          value = prompt,
          onValueChange = { prompt = it },
          modifier = Modifier.weight(1f),
          label = { Text(stringResource(
            if (busy) R.string.playground_next_message else R.string.playground_message_label,
          )) },
          placeholder = { Text(stringResource(R.string.prompt_hint)) },
          // 串流期間仍可編輯下一則；右側按鈕保持取消，絕不自動送出草稿。
          maxLines = if (compact) 2 else 4,
          textStyle = MaterialTheme.typography.bodyLarge,
          shape = AtlasRadius.field,
          colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
          ),
        )
        val actionLabel = stringResource(if (busy) R.string.playground_stop else R.string.action_send)
        FilledIconButton(
          onClick = {
            if (busy) onCancel()
            else if (canSend && prompt.isNotBlank()) {
              submittedPrompt = prompt
              submittedAttachmentName = pendingImage?.image?.filename
              submittedAfterId = acceptedMessage?.id
              val selectedImage = pendingImage
              if (selectedImage != null) onSendVision(prompt, selectedImage.image)
              else onSend(prompt)
            }
          },
          enabled = busy || (canSend && prompt.isNotBlank()),
          modifier = Modifier.size(AtlasSpace.touch).semantics { contentDescription = actionLabel },
          shape = AtlasRadius.pill,
        ) {
          if (busy) {
            Box(
              Modifier.size(16.dp).background(LocalContentColor.current, RoundedCornerShape(AtlasSpace.xs)),
            )
          } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
          }
        }
      }
      pendingImage?.let { attachment ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = AtlasSpace.xs),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        ) {
          Image(
            bitmap = attachment.thumbnail,
            contentDescription = stringResource(R.string.playground_attached_image),
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
          )
          Text(
            attachment.image.filename ?: stringResource(R.string.playground_attached_image),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          IconButton(
            onClick = { pendingImage = null; imageError = false },
            modifier = Modifier.size(AtlasSpace.touch),
          ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.playground_remove_image)) }
        }
      }
      if (imagePreparing) {
        Text(stringResource(R.string.playground_image_loading), style = MaterialTheme.typography.labelMedium)
      }
      if (imageError) {
        Text(
          stringResource(R.string.playground_image_error),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onSelect,
          modifier = Modifier.weight(1f).heightIn(min = AtlasSpace.touch)
            .semantics { contentDescription = switchLabel; stateDescription = selectedContext },
          contentPadding = PaddingValues(horizontal = AtlasSpace.sm, vertical = AtlasSpace.xs),
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
            Text(
              selectedConnection?.modelId ?: stringResource(R.string.connection_choose),
              style = MaterialTheme.typography.labelLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (selectedConnection != null && !compact) {
              Text(
                selectedConnection.name + " · " + selectedConnection.credentialLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        val samplesLabel = stringResource(R.string.section_samples)
        TextButton(
          onClick = onSamples,
          modifier = Modifier.heightIn(min = AtlasSpace.touch).semantics { contentDescription = samplesLabel },
          contentPadding = PaddingValues(horizontal = AtlasSpace.sm),
        ) {
          Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
          if (!compact) {
            Spacer(Modifier.width(AtlasSpace.xs))
            Text(stringResource(R.string.playground_performance))
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubble(
  message: ChatMessage,
  streaming: Boolean,
  canRetry: Boolean,
  retryEnabled: Boolean,
  onRetry: () -> Unit,
) {
  val isUser = message.role == "user"
  val colors = MaterialTheme.colorScheme
  val clipboard = LocalClipboardManager.current
  var copied by remember(message.id, message.text) { mutableStateOf(false) }
  val messageText = remember(message.text, streaming) {
    buildAnnotatedString {
      append(message.text)
      if (streaming) appendInlineContent("stream-cursor", "\u200B")
    }
  }
  val cursor = remember(colors.primary) {
    mapOf("stream-cursor" to InlineTextContent(
      Placeholder(8.sp, 18.sp, PlaceholderVerticalAlign.TextCenter),
    ) {
      Box(Modifier.fillMaxSize().padding(vertical = 2.dp).background(colors.primary, AtlasRadius.pill))
    })
  }
  Box(Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
        .fillMaxWidth(if (isUser) 0.88f else 1f),
      horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs),
    ) {
      FlowRow(
        modifier = Modifier.padding(horizontal = AtlasSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs),
      ) {
        Text(
          text = stringResource(if (isUser) R.string.chat_user_label else R.string.chat_assistant_label),
          style = MaterialTheme.typography.labelMedium,
          color = if (isUser) colors.onSurfaceVariant else colors.primary,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (streaming) {
          val status = stringResource(
            if (message.text.isEmpty()) R.string.playground_waiting else R.string.status_sending,
          )
          AtlasBadge(
            text = status,
            tone = AtlasTone.ACCENT,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          )
        }
      }
      Surface(
        shape = if (isUser) AtlasRadius.card.copy(bottomEnd = CornerSize(AtlasSpace.xs))
          else AtlasRadius.card.copy(topStart = CornerSize(AtlasSpace.xs)),
        color = if (isUser) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (isUser) colors.onPrimaryContainer else colors.onSurface,
      ) {
        Column(Modifier.padding(AtlasSpace.lg), verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
          if (message.attachmentName != null) {
            AtlasBadge(
              text = stringResource(R.string.playground_attached_image) + " · " + message.attachmentName,
              tone = AtlasTone.ACCENT,
            )
          }
          if (message.text.isNotEmpty() || streaming) {
            // 靜態 inline cursor：隨真實 delta 前進，無 blink/timer/無限動畫或額外請求。
            SelectionContainer {
              Text(
                text = messageText,
                inlineContent = cursor,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
          } else {
            Text(
              stringResource(R.string.playground_no_content),
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
            )
          }
        }
      }
      if (!streaming && (message.text.isNotBlank() || canRetry)) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
          verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs),
        ) {
          if (message.text.isNotBlank()) TextButton(
            onClick = { clipboard.setText(AnnotatedString(message.text)); copied = true },
            modifier = Modifier.heightIn(min = AtlasSpace.touch),
          ) {
            if (copied) {
              Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
              Spacer(Modifier.width(AtlasSpace.xs))
            }
            Text(stringResource(if (copied) R.string.playground_copied else R.string.playground_copy))
          }
          if (canRetry) {
            val retryHint = stringResource(R.string.playground_retry_hint)
            TextButton(
              onClick = onRetry,
              enabled = retryEnabled,
              modifier = Modifier.heightIn(min = AtlasSpace.touch).semantics { contentDescription = retryHint },
            ) {
              Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(AtlasSpace.icon))
              Spacer(Modifier.width(AtlasSpace.sm))
              Text(stringResource(R.string.playground_retry))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PlaygroundSheet(
  title: String,
  onDismiss: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val dismiss = {
    focusManager.clearFocus()
    keyboard?.hide()
    onDismiss()
  }
  ModalBottomSheet(
    onDismissRequest = dismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape = AtlasRadius.sheet,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)
        .imePadding()
        .padding(start = AtlasSpace.lg, end = AtlasSpace.lg, bottom = AtlasSpace.lg),
      verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = dismiss, modifier = Modifier.size(AtlasSpace.touch)) {
          Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
      }
      content()
    }
  }
}

@Composable
private fun PlaygroundConnectionSheet(
  connections: List<ConnectionItem>,
  connectionsLoading: Boolean,
  selectedId: String?,
  onSelect: (String) -> Unit,
  onGotoProviders: () -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  fun leaveSheet(action: () -> Unit) {
    focusManager.clearFocus()
    keyboard?.hide()
    action()
  }
  val matches = remember(connections, query, selectedId) {
    val term = query.trim()
    val filtered = if (term.isEmpty()) connections else connections.filter {
      it.modelId.contains(term, ignoreCase = true) || it.name.contains(term, ignoreCase = true) ||
        it.credentialLabel.contains(term, ignoreCase = true) || it.accountId.contains(term, ignoreCase = true)
    }
    filtered.sortedBy { it.id != selectedId }
  }
  PlaygroundSheet(title = stringResource(R.string.playground_switch_model), onDismiss = onDismiss) {
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(AtlasSpace.sm),
      contentPadding = PaddingValues(vertical = AtlasSpace.sm),
    ) {
      // 搜尋框一同捲動，橫向／大字／IME 壓縮時不會吃光結果區。
      item(key = "search", contentType = "search") {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          label = { Text(stringResource(R.string.playground_search_connections)) },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          trailingIcon = if (query.isNotEmpty()) {
            {
              IconButton(onClick = { query = "" }, modifier = Modifier.size(AtlasSpace.touch)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.playground_clear_search))
              }
            }
          } else null,
          singleLine = true,
          shape = AtlasRadius.field,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item(key = "selection-note", contentType = "note") {
        AtlasSectionHeading(
          title = stringResource(R.string.playground_connection_count, matches.size),
          subtitle = stringResource(R.string.playground_switch_hint),
        )
      }
      if (connectionsLoading) {
        item(key = "loading", contentType = "loading") {
          Box(Modifier.fillMaxWidth().padding(AtlasSpace.md), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(AtlasSpace.icon))
          }
        }
      } else if (matches.isEmpty()) {
        item(key = "empty", contentType = "empty") {
          AtlasEmptyState(
            title = stringResource(if (connections.isEmpty()) R.string.connection_none else R.string.playground_no_matches),
            description = stringResource(if (connections.isEmpty()) R.string.playground_connect_hint else R.string.playground_search_hint),
            icon = Icons.Filled.Search,
            compact = true,
          )
        }
      }
      items(matches, key = { "connection:${it.id}" }, contentType = { "connection" }) { connection ->
        val isSelected = connection.id == selectedId
        Surface(
          onClick = { leaveSheet { onSelect(connection.id) } },
          enabled = connection.enabled,
          shape = AtlasRadius.field,
          color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
          contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
          border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch)
            .semantics { selected = isSelected; role = Role.RadioButton },
        ) {
          Row(Modifier.padding(AtlasSpace.md), horizontalArrangement = Arrangement.spacedBy(AtlasSpace.sm)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AtlasSpace.xs)) {
              Text(connection.modelId, style = MaterialTheme.typography.titleSmall)
              Text(connection.name, style = MaterialTheme.typography.bodyMedium)
              Text(
                stringResource(R.string.label_credential) + " · " + connection.credentialLabel,
                style = MaterialTheme.typography.bodySmall,
              )
              if (connection.accountId.isNotBlank()) {
                Text(
                  stringResource(R.string.playground_account) + " · " + connection.accountId,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              // 只顯示連線原有 scope 的真值，不依 model 名稱推斷免費或補零。
              Text(
                stringResource(R.string.label_price) + " · " + connection.priceState,
                style = MaterialTheme.typography.bodySmall,
              )
              Text(
                stringResource(R.string.label_quota) + " · " + connection.quotaState,
                style = MaterialTheme.typography.bodySmall,
              )
              if (!connection.enabled) Text(
                stringResource(R.string.credential_disabled), style = MaterialTheme.typography.labelMedium,
              )
            }
            if (isSelected) Icon(
              Icons.Filled.Check,
              contentDescription = stringResource(R.string.status_selected),
              modifier = Modifier.size(AtlasSpace.icon),
            )
          }
        }
      }
    }
    if (!connectionsLoading) {
      OutlinedButton(
        onClick = { leaveSheet(onGotoProviders) },
        modifier = Modifier.fillMaxWidth().heightIn(min = AtlasSpace.touch),
      ) { Text(stringResource(R.string.action_goto_providers)) }
    }
  }
}

@Composable
private fun PlaygroundSamplesSheet(samples: List<SampleItem>, onDismiss: () -> Unit) {
  PlaygroundSheet(title = stringResource(R.string.section_samples), onDismiss = onDismiss) {
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(vertical = AtlasSpace.sm),
      verticalArrangement = Arrangement.spacedBy(AtlasSpace.md),
    ) {
      item(key = "source", contentType = "note") {
        Text(
          stringResource(R.string.playground_samples_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (samples.isEmpty()) {
        item(key = "empty", contentType = "empty") {
          AtlasEmptyState(
            title = stringResource(R.string.samples_empty),
            description = stringResource(R.string.playground_samples_empty_hint),
            icon = Icons.Filled.Info,
            compact = true,
          )
        }
      }
      items(samples, key = { "sample:${it.id}" }, contentType = { "sample" }) { SampleCard(it) }
    }
  }
}

@Composable
private fun SampleCard(sample: SampleItem) {
  val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
  val started = remember(sample.startedAt) { dateFormat.format(Date(sample.startedAt)) }
  val unknown = stringResource(R.string.value_unknown)
  AtlasCard(subdued = true) {
    Text(sample.modelId, style = MaterialTheme.typography.titleSmall)
    AtlasFact(stringResource(R.string.sample_outcome), sample.outcome)
    AtlasMetrics(listOf(
      stringResource(R.string.sample_latency) to (sample.latencyMs?.let { "$it ms" } ?: unknown),
      stringResource(R.string.sample_tps) to (sample.tps?.let {
        String.format(Locale.getDefault(), "%.1f tokens/s", it)
      } ?: unknown),
      stringResource(R.string.playground_ttft) to (sample.ttftMs?.let { "$it ms" } ?: unknown),
      stringResource(R.string.playground_ttfb) to (sample.ttfbMs?.let { "$it ms" } ?: unknown),
      stringResource(R.string.playground_output_tokens) to (sample.outputTokens?.toString() ?: unknown),
    ))
    AtlasFact(stringResource(R.string.label_price), sample.priceState)
    AtlasFact(stringResource(R.string.label_quota), sample.quotaState)
    Text(
      stringResource(R.string.sample_started) + " · " + started,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
