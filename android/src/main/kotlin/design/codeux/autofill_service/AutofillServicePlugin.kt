package design.codeux.autofill_service

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.assist.AssistStructure
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.autofill.*
import android.view.autofill.*
import android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class PwDataset(
    val label: String,
    val username: String,
    val password: String
)

@RequiresApi(Build.VERSION_CODES.O)
class AutofillServicePlugin(val registrar: Registrar) : MethodCallHandler,
    PluginRegistry.ActivityResultListener, PluginRegistry.NewIntentListener {

    val autofillManager =
        requireNotNull(registrar.activity().getSystemService(AutofillManager::class.java))
    var requestSetAutofillServiceResult: Result? = null
    var lastIntent: Intent? = null

    init {
        val channel = MethodChannel(registrar.messenger(), "codeux.design/autofill_service")
        channel.setMethodCallHandler(this)
        registrar.addActivityResultListener(this)
        registrar.addNewIntentListener(this)
    }

    companion object {
        // some creative way so we have some more or less unique result code? 🤷️
        val REQUEST_CODE_SET_AUTOFILL_SERVICE = AutofillServicePlugin::class.java.hashCode() and 0xffff

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            AutofillServicePlugin(registrar)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "hasAutofillServicesSupport" ->
                result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            "hasEnabledAutofillServices" ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    result.success(null)
                } else {
                    result.success(autofillManager.hasEnabledAutofillServices())
                }
            "disableAutofillServices" -> {
                autofillManager.disableAutofillServices()
                result.success(null)
            }
            "requestSetAutofillService" -> {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
                intent.data = Uri.parse("package:com.example.android.autofill.service");
                logger.debug { "enableService(): intent=$intent" }
                requestSetAutofillServiceResult = result
                registrar.activity()
                    .startActivityForResult(intent, REQUEST_CODE_SET_AUTOFILL_SERVICE)
            }
            "resultWithDataset" -> {
                resultWithDataset(call, result)
            }
            else -> result.notImplemented()
        }
    }

    private fun resultWithDataset(call: MethodCall, result: Result) {
        val label = call.argument<String>("label") ?: "Autofill"
        val username = call.argument<String>("username") ?: ""
        val password = call.argument<String>("password") ?: ""
        if (password.isBlank()) {
            logger.warn { "No known password." }
        }
        resultWithDatasets(listOf(PwDataset(label, username, password)), result)
    }

    private fun resultWithDatasets(pwDatasets: List<PwDataset>, result: Result) {

        val structureParcel =
            lastIntent?.extras?.getParcelable<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
                ?: registrar.activity().intent?.extras?.getParcelable<AssistStructure>(
                    AutofillManager.EXTRA_ASSIST_STRUCTURE
                )
        if (structureParcel == null) {
            logger.info { "No structure available." }
            result.success(false)
            return
        }

        val structure = AssistStructureParser(structureParcel)

        val autofillIds =
            (lastIntent ?: registrar.activity().intent)?.extras?.getParcelableArrayList<AutofillId>(
                "autofillIds"
            )
        logger.debug { "structure: $structure /// autofillIds: $autofillIds" }
        logger.info { "packageName: ${registrar.context().packageName}" }

        val remoteViews = { RemoteViewsHelper.viewsWithNoAuth(
            registrar.context().packageName, "Fill Me"
        )}
        structure.fieldIds.values.forEach { it.sortByDescending { it.heuristic.weight } }

        val datasetResponse = FillResponse.Builder()
            .setAuthentication(
                structure.autoFillIds.toTypedArray(),
                null,
                null
            )
            .apply { pwDatasets.forEach { pw -> addDataset(Dataset.Builder(remoteViews()).apply {
                setId("test ${pw.username}")
                structure.allNodes.forEach { node ->
                    if (node.isFocused && node.autofillId != null) {
                        logger.debug("Setting focus node. ${node.autofillId}")
                        setValue(
                            node.autofillId!!,
                            AutofillValue.forText("focus"),
                            RemoteViews(
                                registrar.context().packageName,
                                android.R.layout.simple_list_item_1
                            ).apply {
                                setTextViewText(android.R.id.text1, pw.label + "(focus)")
                            })

                    }
                }
                structure.fieldIds[AutofillInputType.Email]?.forEach { field ->
                    logger.debug("Adding data set for email ${field.autofillId}")
                    setValue(field.autofillId, AutofillValue.forText(pw.username), RemoteViews(registrar.context().packageName, android.R.layout.simple_list_item_1).apply {
                        setTextViewText(android.R.id.text1, pw.label)
                    })
                }
                structure.fieldIds[AutofillInputType.Password]?.forEach { field ->
                    logger.debug("Adding data set for password ${field.autofillId}")
                    setValue(field.autofillId, AutofillValue.forText(pw.password), RemoteViews(registrar.context().packageName, android.R.layout.simple_list_item_1).apply {
                        setTextViewText(android.R.id.text1, pw.label)
                    })
                }
                structure.fieldIds[AutofillInputType.UserName]?.forEach { field ->
                    logger.debug("Adding data set for username ${field.autofillId}")
                    setValue(field.autofillId, AutofillValue.forText(pw.username), RemoteViews(registrar.context().packageName, android.R.layout.simple_list_item_1).apply {
                        setTextViewText(android.R.id.text1, pw.label)
                    })
                }
            }.build()) } }
            .build()
        val replyIntent = Intent().apply {
            // Send the data back to the service.
            putExtra(EXTRA_AUTHENTICATION_RESULT, datasetResponse)
        }

        registrar.activity().setResult(Activity.RESULT_OK, replyIntent)
        registrar.activity().finish()
        result.success(true)
    }

    override fun onNewIntent(intent: Intent?): Boolean {
        lastIntent = intent
        logger.info {
            "We got a new intent. $intent (extras: ${intent?.extras?.keySet()?.map {
                it to intent.extras?.get(
                    it
                )
            }})"
        }
        return false
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        logger.debug(
            "got activity result for $requestCode" +
                " (our: $REQUEST_CODE_SET_AUTOFILL_SERVICE) result: $resultCode"
        )
        if (requestCode == REQUEST_CODE_SET_AUTOFILL_SERVICE) {
            requestSetAutofillServiceResult?.let { result ->
                requestSetAutofillServiceResult = null
                result.success(resultCode == RESULT_OK)
            } ?: logger.warn { "Got activity result, but did not have a requestResult set." }
            return true
        }
        return false
    }

}
