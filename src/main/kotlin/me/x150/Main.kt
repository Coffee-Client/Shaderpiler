package me.x150

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

val OUTPUT_DIRECTORY: File = File("./dist")
val OUTPUT_DIRECTORY_POST: File = File("./dist/post")
val OUTPUT_DIRECTORY_PROG: File = File("./dist/program")

fun main(args: Array<String>) {
    val firstNotNullOfOrNull = args.firstOrNull() ?: "."
    val f = File(firstNotNullOfOrNull)
    if (!f.isDirectory) {
        println("Input is not a directory")
        exitProcess(1)
    }
    if (OUTPUT_DIRECTORY.exists()) {
        OUTPUT_DIRECTORY.deleteRecursively()
    }
    for (listedFile in f.listFiles()!!) {
        if (listedFile.name.endsWith(".json")) {
            parseJson(listedFile.name, f, listedFile.readText())
        }
    }
}

fun <T> JsonElement.nonNullOrElse(ifNull: T, ifNotNull: JsonElement.() -> T): T {
    return if (this.isJsonNull) {
        ifNull
    } else {
        this.ifNotNull()
    }
}

fun parseJson(name: String, sourceFolder: File, content: String) {
    val p: JsonObject = JsonParser.parseString(content).asJsonObject
    val shaderName = p.get("name").asString
    val sourceFrag = p.get("sourceFrag").nonNullOrElse("blit") { this.asString }
    val sourceVert = p.get("sourceVert").nonNullOrElse("sobel") { this.asString }
    val samplers = p.get("samplers").asJsonArray.map { jsonElement -> jsonElement.asString }
    val uniforms = p.get("uniforms").asJsonObject
    val parsedUniforms = mutableListOf(
        Uniform("ProjMat", "matrix4x4", 16, floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f)),
        Uniform("InSize", "float", 2, floatArrayOf(1f, 1f)),
        Uniform("OutSize", "float", 2, floatArrayOf(1f, 1f)),
    )
    uniforms.keySet().forEachIndexed { i, s ->
        val el = uniforms[s]
        if (el.isJsonObject) {
            val elParsed = el.asJsonObject
            if (!elParsed.has("values")) {
                println("Failed to parse: $name -> uniforms[$i] -> values does not exist")
                return
            }
            parsedUniforms.add(
                Uniform(
                    s,
                    elParsed.get("type").nonNullOrElse("float") { this.asString },
                    elParsed.get("values").asJsonArray.size(),
                    elParsed.get("values").asJsonArray.map { jsonElement -> jsonElement.asFloat }.toFloatArray()
                )
            )
        } else if (el.isJsonArray) {
            val elParsed = el.asJsonArray
            parsedUniforms.add(
                Uniform(s, "float", elParsed.size(), elParsed.map { jsonElement -> jsonElement.asFloat }.toFloatArray())
            )
        }
    }
    val passes = p.get("passes").asJsonArray
    val targets = mutableListOf<String>()
    val parsedPasses = mutableListOf<Pass>()
    for (pass in passes.withIndex()) {
        val passAsJsonObject = pass.value.asJsonObject
        val passName = passAsJsonObject.get("name").asString.let {
            if (it.equals("%")) {
                shaderName
            } else {
                it
            }
        }
        val input = passAsJsonObject.get("input").nonNullOrElse("minecraft:main") { this.asString }
        val output = passAsJsonObject.get("output").nonNullOrElse("minecraft:main") { this.asString }
        if (!targets.contains(input) && !input.startsWith("minecraft:")) targets.add(input)
        if (!targets.contains(output) && !output.startsWith("minecraft:")) targets.add(output)
        val uniformOverrides: JsonObject? = passAsJsonObject.get("uniformOverrides")?.asJsonObject
        val parsedUniformOverrides = mutableListOf<UniformOverride>()
        uniformOverrides?.keySet()?.forEachIndexed { i, s ->
            if (parsedUniforms.none { uniform -> uniform.name == s }) {
                println("Failed to parse: $name -> passes[${pass.index}] -> uniformOverrides[$i] references undefined uniform \"$s\"")
                return
            }
            val toFloatArray = uniformOverrides[s].asJsonArray.map { jsonElement -> jsonElement.asFloat }.toFloatArray()
            val count = parsedUniforms.find { uniform -> uniform.name == s }!!.count
            if (count != toFloatArray.size) {
                println("Failed to parse: $name -> passes[${pass.index}] -> uniformOverrides[$i] references uniform $s with $count values, specifies ${toFloatArray.size}")
                return
            }
            parsedUniformOverrides.add(
                UniformOverride(s, toFloatArray)
            )
        }
        parsedPasses.add(Pass(passName, input, output, parsedUniformOverrides))
    }
    val postJson = JsonObject()
    val gson = GsonBuilder().setPrettyPrinting().create()
    postJson.add("targets", gson.toJsonTree(targets))
    postJson.add("passes", gson.toJsonTree(
        parsedPasses.map { pass ->
            gson.toJsonTree(
                mapOf(
                    "name" to pass.name,
                    "intarget" to pass.input,
                    "outtarget" to pass.output,
                    "uniforms" to pass.uniformOverrides.map { uniformOverride ->
                        gson.toJsonTree(
                            mapOf(
                                "name" to uniformOverride.name,
                                "values" to uniformOverride.value
                            )
                        )
                    }
                )
            )
        }
    ))
    val programJson = gson.toJsonTree(
        mapOf(
            "blend" to mapOf(
                "func" to "add",
                "srcrgb" to "srcalpha",
                "dstrgb" to "1-srcalpha"
            ),
            "vertex" to sourceVert,
            "fragment" to sourceFrag,
            "attributes" to arrayOf("Position"),
            "samplers" to samplers.map { s -> gson.toJsonTree(mapOf("name" to s)) },
            "uniforms" to parsedUniforms.map { uniform -> gson.toJsonTree(mapOf(
                "name" to uniform.name,
                "type" to uniform.type,
                "count" to uniform.count,
                "values" to uniform.values
            ))}
        )
    )
    ensureFilesExist()
    val parsedShaderName = shaderName.split(":")[1]
    val outputPost = File(OUTPUT_DIRECTORY_POST, "$parsedShaderName.json")
    val outputProgram = File(OUTPUT_DIRECTORY_PROG, "$parsedShaderName.json")
    val outputSourceFrag = File(OUTPUT_DIRECTORY_PROG, "$parsedShaderName.fsh")
    val outputSourceVert = File(OUTPUT_DIRECTORY_PROG, "$parsedShaderName.vsh")
    outputPost.writeText(gson.toJson(postJson))
    outputProgram.writeText(gson.toJson(programJson))
    val file = File(sourceFolder, sourceFrag)
    if (file.exists()) {
        file.copyTo(outputSourceFrag, overwrite = true)
    }
    val file1 = File(sourceFolder, sourceVert)
    if (file1.exists()) {
        file1.copyTo(outputSourceVert, overwrite = true)
    }
    println("Compiled shader $parsedShaderName")
}

fun ensureFilesExist() {
    if (!OUTPUT_DIRECTORY_POST.exists()) OUTPUT_DIRECTORY_POST.mkdirs()
    if (!OUTPUT_DIRECTORY_PROG.exists()) OUTPUT_DIRECTORY_PROG.mkdirs()
}