class FlowCases {
    open class Plain
    class PlainChild : Plain()
    open class Custom {
        override fun toString(): String = field.toString()
    }
    class CustomChild : Custom()
    class CustomHash {
        override fun hashCode(): Int = field.toString().hashCode()
    }

    companion object {
        @JvmField var field: Any? = null
        @JvmStatic fun safeObject(): String = java.lang.String.valueOf(Any())
        @JvmStatic fun safeAlias(): String {
            val original: Any = Any()
            val alias = original
            return alias.toString()
        }
        @JvmStatic fun safeEscapedAlias(): String {
            val original: Any = Any()
            field = original
            return original.toString()
        }
        @JvmStatic fun safeMerge(branch: Boolean): String {
            val value: Any = if (branch) Any() else "literal"
            return value.toString()
        }
        @JvmStatic fun safeNull(): String = java.lang.String.valueOf(null as Any?)
        @JvmStatic fun safeNullableStaticMerge(branch: Boolean): String {
            val value: Any? = if (branch) Any() else null
            return value.toString()
        }
        @JvmStatic fun safeCast(): String {
            val value: Any = "literal"
            return (value as CharSequence).toString()
        }
        @JvmStatic fun safeLoop(count: Int): String {
            var value: Any = Any()
            for (i in 0 until count) value = Any()
            return value.toString()
        }
        @JvmStatic fun safeWideConcat(left: Long, right: Double): String {
            val value: Any = Any()
            return "$left:$value:$right"
        }
        @JvmStatic fun blockedExplicitBuilderConcat(left: Long, right: Double): String =
            StringBuilder().append(left).append(':').append(Any()).append(':').append(right).toString()
        @JvmStatic fun safeInheritedDefault(): String = PlainChild().toString()
        @JvmStatic fun blockedChanged(): String = object {}.toString()
        @JvmStatic fun blockedMerge(branch: Boolean): String {
            val value: Any = if (branch) Any() else object {}
            return value.toString()
        }
        @JvmStatic fun blockedUnknownMerge(branch: Boolean, parameter: Any): String {
            val value: Any = if (branch) Any() else parameter
            return value.toString()
        }
        @JvmStatic fun blockedField(): String = field.toString()
        private fun factory(): Any = Any()
        @JvmStatic fun blockedReturn(): String = factory().toString()
        @JvmStatic fun blockedCustom(): String = Custom().toString()
        @JvmStatic fun blockedInheritedCustom(): String = CustomChild().toString()
        @JvmStatic fun blockedHashOverride(): String = CustomHash().toString()
        @JvmStatic fun blockedBuilderInsert(): String {
            val builder = StringBuilder()
            builder.insert(0, object {})
            return builder.toString()
        }
        @JvmStatic fun blockedBufferInsert(): String {
            val buffer = StringBuffer()
            buffer.insert(0, object {})
            return buffer.toString()
        }
        @JvmStatic fun blockedPrintSafeObject() { println(Any()) }
        @JvmStatic fun blockedArray(): String = arrayOf(object {}).contentDeepToString()
        @JvmStatic fun blockedEmptyReferenceArray(): String = emptyArray<Any>().contentToString()
        @JvmStatic fun blockedCollection(): String = listOf(Any()).toString()
        @JvmStatic fun blockedCast(parameter: Any): String = (parameter as Plain).toString()
        @JvmStatic fun blockedLoop(count: Int): String {
            var value: Any = Any()
            for (i in 0 until count) value = object {}
            return value.toString()
        }
        @JvmStatic fun blockedWideConcat(left: Long, right: Double): String {
            val value: Any = object {}
            return "$left:$value:$right"
        }
    }
}
