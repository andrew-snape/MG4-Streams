package com.mg4.streams

import java.io.File

/**
 * Favourite stations, saved by stream URL, one per line, in a text file. Parsing and formatting
 * are pure; load and save do file I/O, so call them on the worker thread.
 */
class FavouritesStore(private val file: File) {

    fun load(): LinkedHashSet<String> =
        if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(LinkedHashSet()) else LinkedHashSet()

    fun save(urls: Collection<String>) {
        val text = format(urls)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    companion object {
        fun parse(text: String): LinkedHashSet<String> {
            val set = LinkedHashSet<String>()
            text.lineSequence().map { it.trim() }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .forEach { set += it }
            return set
        }

        fun format(urls: Collection<String>): String =
            urls.joinToString("") { it + "\n" }

        /** Adds [url] if absent, otherwise removes it. Returns true if it is now a favourite. */
        fun toggle(set: MutableSet<String>, url: String): Boolean =
            if (set.remove(url)) false else { set.add(url); true }
    }
}
