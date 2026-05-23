package com.example.network

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class AgentLLMService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getNextAction(
        provider: String, // "OLLAMA" or "GEMINI"
        hostUrl: String,  // for Ollama, e.g. "http://127.0.0.1:11434"
        modelName: String,// for Ollama, e.g. "llama3.2"
        taskGoal: String,
        screenNodes: String,
        actionHistory: List<String>,
        currentPackage: String = "",
        isYouTube: Boolean = false,
        isWhatsApp: Boolean = false,
        isNativeOrCanvas: Boolean = false
    ): AgentActionResponse {
        val systemPrompt = """
            You are "Android Agent", a phone automation system. Output strictly one valid JSON block.
            For YouTube player tasks, always tap video surface first to reveal controls.
            After every action, refresh screen state. Prefer coordinate gestures (CLICK_COORDS) on YouTube, WhatsApp, overlays, or native surfaces.

            AVAILABLE ACTIONS:
            1. OPEN_APP: {"action":"OPEN_APP","package":"package.name"} (Helpers: com.whatsapp, com.google.android.youtube, com.android.settings, com.android.chrome)
            2. CLICK_NODE: {"action":"CLICK_NODE","nodeId":12,"fallbackToCoords":true}
            3. LONG_CLICK_NODE: {"action":"LONG_CLICK_NODE","nodeId":12,"fallbackToCoords":true,"durationMs":750}
            4. CLICK_COORDS: {"action":"CLICK_COORDS","pctX":0.5,"pctY":0.32} (pctX, pctY: float 0.0 to 1.0)
            5. LONG_CLICK_COORDS: {"action":"LONG_CLICK_COORDS","pctX":0.5,"pctY":0.6,"durationMs":750}
            6. SWIPE: {"action":"SWIPE","pctX":0.5,"pctY":0.8,"pctEndX":0.5,"pctEndY":0.2,"durationMs":450} (Scroll/Swipe)
            7. INPUT_TEXT: {"action":"INPUT_TEXT","nodeId":12,"text":"hello"}
            8. PRESS_BACK: {"action":"PRESS_BACK"}
            9. PRESS_HOME: {"action":"PRESS_HOME"}
            10. WAIT: {"action":"WAIT","durationMs":1000}
            11. DONE: {"action":"DONE","text":"success summary"}
            12. FAIL: {"action":"FAIL","text":"error summary"}

            OUTPUT SCHEMA (Strict single raw JSON chunk only, no extra wrapping text, no markdown block quotes):
            {
              "thought": "Reasoning about layout elements.",
              "action": "ACTION_NAME",
              "package": "optional_package",
              "nodeId": -1,
              "text": "text value if typing/finishing",
              "pctX": -1.0,
              "pctY": -1.0,
              "pctEndX": -1.0,
              "pctEndY": -1.0,
              "durationMs": 500
            }
        """.trimIndent()

        val appHints = java.lang.StringBuilder()
        if (isYouTube) {
            appHints.append("IMPORTANT: YouTube hides player buttons. First tap the player/video surface before looking for fullscreen/settings/speed.\n")
        }
        if (isWhatsApp) {
            appHints.append("IMPORTANT: For reactions, long press the message using physical gesture, then refresh all windows and choose emoji overlay.\n")
        }
        if (isNativeOrCanvas) {
            appHints.append("IMPORTANT: Native/GL/Canvas screens may not expose buttons. Use coordinate gestures.\n")
        }

        val userPrompt = """
            User Task/Goal: "$taskGoal"
            Current Package/App Context: "$currentPackage"
            
            $appHints
            
            Ranked Screen Elements (Top 20 Interactive):
            $screenNodes
            
            Action History in Session:
            ${if (actionHistory.isEmpty()) "None" else actionHistory.joinToString("\n")}
            
            Determine the next action. Output strictly valid JSON matching the schema.
        """.trimIndent()

        return withContext(Dispatchers.IO) {
            if (provider == "GEMINI") {
                callGeminiAPI(systemPrompt, userPrompt)
            } else {
                callOllamaAPI(hostUrl, modelName, systemPrompt, userPrompt)
            }
        }
    }

    private fun callOllamaAPI(
        hostUrl: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String
    ): AgentActionResponse {
        val fullPrompt = "$systemPrompt\n\n$userPrompt"
        val cleanHost = hostUrl.trimEnd('/')
        val url = "$cleanHost/api/generate"

        return try {
            val requestBodyJson = JSONObject()
            requestBodyJson.put("model", modelName)
            requestBodyJson.put("prompt", fullPrompt)
            requestBodyJson.put("stream", false)
            requestBodyJson.put("format", "json")

            val body = requestBodyJson.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AgentActionResponse(
                        thought = "Could not reach local Ollama server.",
                        action = "FINISH",
                        text = "Ollama connection failed: HTTP ${response.code}. Ensure laptop Ollama matches model '$modelName', you ran 'adb reverse tcp:11434 tcp:11434', and Ollama is active."
                    )
                }
                val rawBodyText = response.body?.string() ?: ""
                val responseJson = JSONObject(rawBodyText)
                val textResponse = responseJson.getString("response")
                
                parseAgentResponse(textResponse)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AgentActionResponse(
                thought = "Connection error while reaching local Ollama model.",
                action = "FINISH",
                text = "Network connection failed. Verify Laptop connection. Run 'adb reverse tcp:11434 tcp:11434'. Error: ${e.message}"
            )
        }
    }

    private fun callGeminiAPI(
        systemPrompt: String,
        userPrompt: String
    ): AgentActionResponse {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isBlank()) {
            return AgentActionResponse(
                thought = "Gemini key is placeholder.",
                action = "FINISH",
                text = "API Key not configured. Please add your GEMINI_API_KEY to AI Studio Secrets panel."
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        return try {
            val bodyJson = JSONObject()
            val contentsArray = org.json.JSONArray()
            
            val systemInstructionJson = JSONObject()
            val systemPartsArray = org.json.JSONArray()
            val systemPartText = JSONObject()
            systemPartText.put("text", systemPrompt)
            systemPartsArray.put(systemPartText)
            systemInstructionJson.put("parts", systemPartsArray)
            bodyJson.put("systemInstruction", systemInstructionJson)

            val userContentJson = JSONObject()
            userContentJson.put("role", "user")
            val userPartsArray = org.json.JSONArray()
            val userPartText = JSONObject()
            userPartText.put("text", userPrompt)
            userPartsArray.put(userPartText)
            userContentJson.put("parts", userPartsArray)
            contentsArray.put(userContentJson)
            bodyJson.put("contents", contentsArray)

            val generationConfig = JSONObject()
            generationConfig.put("responseMimeType", "application/json")
            bodyJson.put("generationConfig", generationConfig)

            val body = bodyJson.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AgentActionResponse(
                        thought = "Could not contact Gemini API.",
                        action = "FINISH",
                        text = "Gemini failure: HTTP ${response.code} - ${response.message}"
                    )
                }
                
                val rawBodyText = response.body?.string() ?: ""
                val responseJson = JSONObject(rawBodyText)
                
                val candidatesArray = responseJson.getJSONArray("candidates")
                if (candidatesArray.length() == 0) {
                    return AgentActionResponse(
                        thought = "Gemini returned no candidates.",
                        action = "FINISH",
                        text = "Empty Gemini response."
                    )
                }
                val candidateObj = candidatesArray.getJSONObject(0)
                val contentObj = candidateObj.getJSONObject("content")
                val partsArray = contentObj.getJSONArray("parts")
                val responseText = partsArray.getJSONObject(0).getString("text")

                parseAgentResponse(responseText)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AgentActionResponse(
                thought = "API connection error to Gemini.",
                action = "FINISH",
                text = "Gemini Connection exception: ${e.message}"
            )
        }
    }

    private fun parseAgentResponse(rawText: String): AgentActionResponse {
        var cleanText = rawText.trim()
        
        // Extract the JSON portion between first '{' and last '}'
        val startIndex = cleanText.indexOf('{')
        val endIndex = cleanText.lastIndexOf('}')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            cleanText = cleanText.substring(startIndex, endIndex + 1)
        } else if (cleanText.startsWith("```")) {
            cleanText = cleanText.substringAfter("{")
            cleanText = "{" + cleanText.substringBeforeLast("}") + "}"
        }

        return try {
            val json = JSONObject(cleanText)
            AgentActionResponse(
                thought = json.optString("thought", "No thought provided"),
                action = json.optString("action", "WAIT"),
                package_name = json.optString("package", ""),
                targetId = json.optInt("targetId", -1),
                text = json.optString("text", ""),
                pctX = json.optDouble("pctX", -1.0).toFloat(),
                pctY = json.optDouble("pctY", -1.0).toFloat(),
                pctEndX = json.optDouble("pctEndX", -1.0).toFloat(),
                pctEndY = json.optDouble("pctEndY", -1.0).toFloat(),
                durationMs = json.optLong("durationMs", 500L)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            val thought = Regex("\"thought\"\\s*:\\s*\"([^\"]*)\"").find(cleanText)?.groupValues?.get(1) ?: "Parsing model JSON failed, falls back."
            val action = Regex("\"action\"\\s*:\\s*\"([^\"]*)\"").find(cleanText)?.groupValues?.get(1) ?: "FINISH"
            val pkg = Regex("\"package\"\\s*:\\s*\"([^\"]*)\"").find(cleanText)?.groupValues?.get(1) ?: ""
            val id = Regex("\"targetId\"\\s*:\\s*(-?\\d+)").find(cleanText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val text = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(cleanText)?.groupValues?.get(1) ?: cleanText
            val pctX = Regex("\"pctX\"\\s*:\\s*([0-9.]+)").find(cleanText)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            val pctY = Regex("\"pctY\"\\s*:\\s*([0-9.]+)").find(cleanText)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            val pctEndX = Regex("\"pctEndX\"\\s*:\\s*([0-9.]+)").find(cleanText)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            val pctEndY = Regex("\"pctEndY\"\\s*:\\s*([0-9.]+)").find(cleanText)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            val durationMs = Regex("\"durationMs\"\\s*:\\s*([0-9]+)").find(cleanText)?.groupValues?.get(1)?.toLongOrNull() ?: 500L
            
            AgentActionResponse(thought, action, pkg, id, text, pctX, pctY, pctEndX, pctEndY, durationMs)
        }
    }
}

data class AgentActionResponse(
    val thought: String,
    val action: String,
    val package_name: String = "",
    val targetId: Int = -1,
    val text: String = "",
    val pctX: Float = -1f,
    val pctY: Float = -1f,
    val pctEndX: Float = -1f,
    val pctEndY: Float = -1f,
    val durationMs: Long = 500L
)
