package com.listopia.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil.compose.AsyncImage
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ink = Color(0xFF10131D)
private val panel = Color(0xFF1B2030)
private val accent = Color(0xFF8B7CFF)
private val http = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
private val gson = Gson()

enum class ProjectType(val label: String) { TOP10("Top 10"), VS("Character VS"), THEN_NOW("Then vs Now") }
data class AssetSlot(val label: String, val query: String, var localPath: String? = null, var sourceUrl: String? = null, var error: String? = null)
data class ProjectData(
    val name: String, val type: String, var title: String, var description: String,
    var hashtags: String, var research: String, var script: String, val assets: MutableList<AssetSlot>,
    val folder: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = ink, surface = panel)) { ListopiaApp() } }
    }
}

private fun securePrefs(context: Context) = runCatching {
    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(context, "listopia_keys", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
}.getOrThrow()

@Composable
fun ListopiaApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { securePrefs(context) }
    var screen by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf(ProjectType.TOP10) }
    var idea by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf(prefs.getString("gemini", "") ?: "") }
    var pixabayKey by remember { mutableStateOf(prefs.getString("pixabay", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var project by remember { mutableStateOf<ProjectData?>(null) }
    var projects by remember { mutableStateOf(loadProjects(context)) }
    var titleEdit by remember { mutableStateOf("") }
    var descriptionEdit by remember { mutableStateOf("") }
    var hashtagsEdit by remember { mutableStateOf("") }
    var researchEdit by remember { mutableStateOf("") }
    var scriptEdit by remember { mutableStateOf("") }
    var replaceIndex by remember { mutableIntStateOf(-1) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val p = project
        if (uri != null && p != null && replaceIndex >= 0) scope.launch {
            runCatching {
                val target = File(p.folder, "Images/manual_${replaceIndex + 1}.jpg")
                target.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Cannot read selected image")
                p.assets[replaceIndex].localPath = target.absolutePath
                p.assets[replaceIndex].error = null
                saveProject(p)
                project = p.copy(assets = p.assets.toMutableList())
            }.onFailure { message = it.message ?: "Image import failed" }
            replaceIndex = -1
        }
    }

    fun saveKeys() {
        prefs.edit().putString("gemini", geminiKey.trim()).putString("pixabay", pixabayKey.trim()).apply()
        message = "API keys saved securely on this device."
    }

    suspend fun testKey(which: String): String = withContext(Dispatchers.IO) {
        val key = if (which == "Gemini") geminiKey.trim() else pixabayKey.trim()
        require(key.isNotBlank()) { "Enter your $which API key first." }
        val url = if (which == "Gemini")
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${key}"
        else "https://pixabay.com/api/?key=${key}&q=test&image_type=photo&per_page=3"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("$which test failed (HTTP ${response.code}). Check the key and try again.")
            "$which API connection successful."
        }
    }

    suspend fun generate() {
        require(geminiKey.isNotBlank()) { "Add your Gemini API key in Settings." }
        require(pixabayKey.isNotBlank()) { "Add your Pixabay API key in Settings." }
        if (selected == ProjectType.VS) require(first.isNotBlank() && second.isNotBlank()) { "Enter both character names." }
        else require(idea.isNotBlank()) { "Enter a video idea." }
        busy = true; message = ""; progress = "Researching topic…"
        try {
            val requestPrompt = buildPrompt(selected, idea, first, second)
            val raw = withContext(Dispatchers.IO) { callGemini(geminiKey.trim(), requestPrompt) }
            val json = parseJson(raw)
            val folderName = safeName(if (selected == ProjectType.VS) "${first}_vs_${second}" else idea)
            val root = File(context.getExternalFilesDir(null), "Listopia/Projects")
            val folder = File(root, folderName + "_" + System.currentTimeMillis().toString().takeLast(5))
            folder.mkdirs()
            val slots = buildSlots(selected, json)
            val p = ProjectData(folder.name, selected.label,
                json.str("title"), json.str("description"), json.str("hashtags"),
                json.str("research"), json.str("script"), slots, folder.absolutePath)
            project = p
            titleEdit = p.title; descriptionEdit = p.description; hashtagsEdit = p.hashtags
            researchEdit = p.research; scriptEdit = p.script
            progress = "Searching and downloading images…"
            for ((i, slot) in p.assets.withIndex()) {
                progress = "Downloading image ${i + 1} of ${p.assets.size}: ${slot.label}"
                try {
                    val image = withContext(Dispatchers.IO) { searchAndDownload(pixabayKey.trim(), slot.query, File(folder, "Images")) }
                    slot.localPath = image.first; slot.sourceUrl = image.second
                } catch (e: Exception) {
                    slot.error = e.message?.take(160) ?: "No suitable Pixabay image found"
                }
            }
            progress = "Saving project…"
            p.title = titleEdit; p.description = descriptionEdit; p.hashtags = hashtagsEdit
            p.research = researchEdit; p.script = scriptEdit
            saveProject(p)
            projects = loadProjects(context)
            progress = ""
            message = "Project created. Review images and edit your content before exporting."
            screen = "editor"
        } catch (e: Exception) {
            progress = ""
            message = e.message ?: "Generation failed. Check your connection and API settings."
        } finally { busy = false }
    }

    Scaffold(containerColor = ink, topBar = {
        TopAppBar(title = { Text("LISTOPIA", fontWeight = FontWeight.Black) },
            actions = { IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ink, titleContentColor = Color.White))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (screen) {
                "home" -> {
                    Text("Turn ideas into video-ready content.", color = Color.LightGray)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { screen = "create" }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Create New Project")
                    }
                    Spacer(Modifier.height(20.dp)); Text("CHOOSE A VIDEO TYPE", color = accent, fontWeight = FontWeight.Bold)
                    ProjectType.entries.forEach { type ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { selected = type; screen = "create" },
                            colors = CardDefaults.cardColors(containerColor = panel)) {
                            Text(type.label, Modifier.padding(18.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(20.dp)); Text("PREVIOUS PROJECTS", color = accent, fontWeight = FontWeight.Bold)
                    if (projects.isEmpty()) Text("Your saved projects will appear here.", color = Color.Gray, modifier = Modifier.padding(12.dp))
                    projects.forEach { f ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            runCatching { project = readProject(f); project?.let { titleEdit=it.title; descriptionEdit=it.description; hashtagsEdit=it.hashtags; researchEdit=it.research; scriptEdit=it.script; screen="editor" } }
                                .onFailure { message = "Could not open project: ${it.message}" }
                        }, colors = CardDefaults.cardColors(containerColor = panel)) {
                            Text(f.name, Modifier.padding(14.dp), color = Color.White)
                        }
                    }
                }
                "create" -> {
                    Text("NEW PROJECT", color = accent, fontWeight = FontWeight.Bold)
                    ProjectType.entries.forEach { type ->
                        FilterChip(selected == type, onClick = { selected = type }, label = { Text(type.label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (selected == ProjectType.VS) {
                        OutlinedTextField(first, { first=it }, label={Text("Character 1")}, modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(second, { second=it }, label={Text("Character 2")}, modifier=Modifier.fillMaxWidth())
                    } else OutlinedTextField(idea, { idea=it }, label={Text("Video idea / topic")}, modifier=Modifier.fillMaxWidth(), minLines=2)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { scope.launch { generate() } }, enabled = !busy, modifier=Modifier.fillMaxWidth()) { Text("Generate project") }
                    if (busy) { Spacer(Modifier.height(14.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress, color=Color.LightGray, modifier=Modifier.padding(top=8.dp)) }
                }
                "settings" -> {
                    Text("API SETTINGS", color=accent, fontWeight=FontWeight.Bold)
                    OutlinedTextField(geminiKey, { geminiKey=it }, label={Text("Gemini API key")}, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(pixabayKey, { pixabayKey=it }, label={Text("Pixabay API key")}, modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick={saveKeys()}, modifier=Modifier.fillMaxWidth()) { Text("Save API keys") }
                    OutlinedButton(onClick={scope.launch { message = runCatching { testKey("Gemini") }.getOrElse { it.message ?: "Test failed" } }}, modifier=Modifier.fillMaxWidth()) { Text("Test Gemini") }
                    OutlinedButton(onClick={scope.launch { message = runCatching { testKey("Pixabay") }.getOrElse { it.message ?: "Test failed" } }}, modifier=Modifier.fillMaxWidth()) { Text("Test Pixabay") }
                    Text("Keys are stored in encrypted Android preferences and are not written into project files.", color=Color.Gray)
                }
                "editor" -> project?.let { p ->
                    Text(p.name, color=accent, fontWeight=FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f)) {
                        item { EditField("Title", titleEdit) { titleEdit=it }; EditField("Description", descriptionEdit, 4) { descriptionEdit=it }; EditField("Hashtags", hashtagsEdit, 2) { hashtagsEdit=it }; EditField("Research", researchEdit, 5) { researchEdit=it }; EditField("Script / points", scriptEdit, 5) { scriptEdit=it } }
                        itemsIndexed(p.assets) { index, asset ->
                            Card(Modifier.fillMaxWidth().padding(vertical=5.dp), colors=CardDefaults.cardColors(containerColor=panel)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("${index+1}. ${asset.label}", color=Color.White, fontWeight=FontWeight.Bold)
                                    if (asset.localPath != null) AsyncImage(model=File(asset.localPath!!), contentDescription=asset.label, modifier=Modifier.fillMaxWidth().height(150.dp))
                                    else Text(asset.error ?: "Image unavailable — import a suitable image.", color=Color(0xFFFFC46B))
                                    OutlinedButton(onClick={ replaceIndex=index; gallery.launch("image/*") }) { Text(if(asset.localPath==null) "Import image" else "Replace image") }
                                    if (asset.error != null) Text("Pixabay: ${asset.error}", color=Color.Gray)
                                }
                            }
                        }
                        item {
                            Button(onClick={
                                p.title=titleEdit; p.description=descriptionEdit; p.hashtags=hashtagsEdit; p.research=researchEdit; p.script=scriptEdit
                                runCatching { saveProject(p); projects=loadProjects(context); message="Project saved." }.onFailure { message=it.message ?: "Save failed" }
                            }, modifier=Modifier.fillMaxWidth()) { Text("Save project") }
                        }
                    }
                }
            }
            if (message.isNotBlank()) Text(message, color=Color(0xFFFFD18A), modifier=Modifier.padding(vertical=8.dp))
        }
    }
}

@Composable private fun EditField(label: String, value: String, lines: Int = 2, change: (String)->Unit) {
    OutlinedTextField(value, change, label={Text(label)}, modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), minLines=lines)
}

private fun buildPrompt(type: ProjectType, idea: String, a: String, b: String): String {
    val subject = if(type==ProjectType.VS) "Compare $a and $b" else idea
    val details = when(type) {
        ProjectType.TOP10 -> "Return exactly 10 ranked items in a numbered list. Create 11 imageSlots: intro plus one slot for each item, in ranking order."
        ProjectType.VS -> "Compare the two named fictional characters. Return balanced categories and a fair verdict. Create exactly 6 imageSlots: 3 for $a and 3 for $b. Do not imply Pixabay imagery is the exact character."
        ProjectType.THEN_NOW -> "Create exactly five historically grounded THEN points and five corresponding NOW points. Create 11 imageSlots: intro, five THEN and five NOW. State when authentic historical imagery may be unavailable."
    }
    return """You are a careful YouTube research assistant. Research: $subject
$details
Use factual, engaging, concise English. Do not invent facts; distinguish uncertainty and cite source names/URLs in research when possible. Return ONLY valid JSON with keys title, description, hashtags, research, script, imageSlots. imageSlots must be an array of objects {label, query}. Queries should describe the actual subject, not generic unrelated pictures. For VS use separate exact character name queries; image search may not find licensed character art. For historical THEN slots request authentic period photos and don't substitute modern illustrations. Keep script structured and useful for a 30-60 second video. """
}

private fun callGemini(key: String, prompt: String): String {
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + URLEncoder.encode(key, "UTF-8")
    val body = JsonObject().apply {
        add("contents", JsonArray().apply { add(JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", prompt) }) }) }) })
        add("generationConfig", JsonObject().apply { addProperty("temperature", 0.35); addProperty("responseMimeType", "application/json") })
    }.toString().toRequestBody("application/json".toMediaType())
    val req = Request.Builder().url(url).post(body).build()
    http.newCall(req).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Gemini request failed (HTTP ${response.code}). Check API key, quota and connection.")
        val root = JsonParser.parseString(text).asJsonObject
        return root.getAsJsonArray("candidates")?.get(0)?.asJsonObject?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString
            ?: error("Gemini returned no content.")
    }
}

private fun parseJson(raw: String): JsonObject {
    val clean = raw.substringAfter("{", "").let { "{$it" }.substringBeforeLast("}") + "}"
    return runCatching { JsonParser.parseString(clean).asJsonObject }.getOrElse { error("Could not read Gemini's structured response. Try generating again.") }
}
private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

private fun buildSlots(type: ProjectType, json: JsonObject): MutableList<AssetSlot> {
    val expected = when(type) { ProjectType.TOP10, ProjectType.THEN_NOW -> 11; ProjectType.VS -> 6 }
    val arr = json.getAsJsonArray("imageSlots")
    val out = mutableListOf<AssetSlot>()
    if (arr != null) arr.forEachIndexed { i, el ->
        val o = el.asJsonObject
        out.add(AssetSlot(o.str("label").ifBlank { "Image ${i+1}" }, o.str("query").ifBlank { o.str("label") }))
    }
    while(out.size < expected) out.add(AssetSlot("Image ${out.size+1}", ""))
    return out.take(expected).toMutableList()
}

private fun searchAndDownload(key: String, query: String, dir: File): Pair<String,String> {
    require(query.isNotBlank()) { "Gemini did not provide an image search query" }
    val url = "https://pixabay.com/api/?key=${URLEncoder.encode(key,"UTF-8")}&q=${URLEncoder.encode(query,"UTF-8")}&image_type=photo&safesearch=true&per_page=5"
    val req = Request.Builder().url(url).get().build()
    val imageUrl = http.newCall(req).execute().use { response ->
        if(!response.isSuccessful) error("Pixabay search failed (HTTP ${response.code})")
        val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
        val hits = root.getAsJsonArray("hits")
        if(hits == null || hits.size()==0) error("No suitable Pixabay image found")
        hits[0].asJsonObject.get("webformatURL").asString
    }
    dir.mkdirs()
    val imageReq = Request.Builder().url(imageUrl).get().build()
    val path = File(dir, "image_${System.currentTimeMillis()}_${safeName(query).take(24)}.jpg")
    http.newCall(imageReq).execute().use { response ->
        if(!response.isSuccessful) error("Image download failed (HTTP ${response.code})")
        response.body?.byteStream()?.use { input -> FileOutputStream(path).use { output -> input.copyTo(output) } }
            ?: error("Empty image response")
    }
    return path.absolutePath to imageUrl
}

private fun safeName(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifBlank { "project" }
private fun saveProject(p: ProjectData) {
    val dir = File(p.folder); dir.mkdirs()
    File(dir, "Title.txt").writeText(p.title)
    File(dir, "Description.txt").writeText(p.description)
    File(dir, "Hashtags.txt").writeText(p.hashtags)
    File(dir, "Research.txt").writeText(p.research)
    File(dir, "Script.txt").writeText(p.script)
    val root = JsonObject().apply {
        addProperty("name",p.name); addProperty("type",p.type); addProperty("folder",p.folder)
        addProperty("title",p.title); addProperty("description",p.description); addProperty("hashtags",p.hashtags)
        addProperty("research",p.research); addProperty("script",p.script)
        add("assets",JsonArray().apply { p.assets.forEach { s -> add(JsonObject().apply { addProperty("label",s.label); addProperty("query",s.query); addProperty("localPath",s.localPath); addProperty("sourceUrl",s.sourceUrl); addProperty("error",s.error) }) } })
    }
    File(dir,"project.json").writeText(gson.toJson(root))
}
private fun readProject(dir: File): ProjectData {
    val j=JsonParser.parseString(File(dir,"project.json").readText()).asJsonObject
    val assets=j.getAsJsonArray("assets").map { e -> val o=e.asJsonObject; AssetSlot(o.str("label"),o.str("query"),o.get("localPath")?.takeUnless{it.isJsonNull}?.asString,o.get("sourceUrl")?.takeUnless{it.isJsonNull}?.asString,o.get("error")?.takeUnless{it.isJsonNull}?.asString) }.toMutableList()
    return ProjectData(j.str("name"),j.str("type"),j.str("title"),j.str("description"),j.str("hashtags"),j.str("research"),j.str("script"),assets,j.str("folder"))
}
private fun loadProjects(context: Context): List<File> {
    val root=File(context.getExternalFilesDir(null),"Listopia/Projects")
    return root.listFiles()?.filter { File(it,"project.json").exists() }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
