package me.x150

class Uniform(val name: String, val type: String, val count: Int, val values: FloatArray) {
    override fun toString(): String {
        return "${javaClass.simpleName}{name=$name,type=$type,count=$count,values=${values.contentToString()}}"
    }
}