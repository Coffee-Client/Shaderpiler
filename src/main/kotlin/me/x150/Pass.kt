package me.x150

class UniformOverride(val name: String, val value: FloatArray) {
    override fun toString(): String {
        return "${javaClass.simpleName}{name=$name,value=${value.contentToString()}}"
    }
}

class Pass(
    val name: String,
    val input: String,
    val output: String,
    val uniformOverrides: MutableList<UniformOverride>
) {
    override fun toString(): String {
        return "${javaClass.simpleName}{name=$name,input=$input,output=$output,uniformOverrides=$uniformOverrides}"
    }
}