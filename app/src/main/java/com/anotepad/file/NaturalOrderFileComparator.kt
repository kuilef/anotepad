package com.anotepad.file

import com.anotepad.data.FileSortOrder
import java.util.Locale

object NaturalOrderFileComparator {
    private val comparator = Comparator<DocumentNode> { left, right ->
        compareNamesNatural(left.name, right.name)
    }

    fun sort(entries: List<DocumentNode>, order: FileSortOrder): List<DocumentNode> {
        return when (order) {
            FileSortOrder.NAME_DESC -> entries.sortedWith(comparator.reversed())
            FileSortOrder.NAME_ASC -> entries.sortedWith(comparator)
        }
    }

    fun compareNamesNatural(left: String, right: String): Int {
        if (left == right) return 0
        val locale = Locale.getDefault()
        var leftIndex = 0
        var rightIndex = 0
        val leftLength = left.length
        val rightLength = right.length

        while (leftIndex < leftLength && rightIndex < rightLength) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            val leftIsDigit = leftChar.isDigit()
            val rightIsDigit = rightChar.isDigit()

            if (leftIsDigit && rightIsDigit) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < leftLength && left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < rightLength && right[rightIndex].isDigit()) rightIndex++
                val leftRun = left.substring(leftStart, leftIndex)
                val rightRun = right.substring(rightStart, rightIndex)
                val leftTrim = leftRun.trimStart('0').ifEmpty { "0" }
                val rightTrim = rightRun.trimStart('0').ifEmpty { "0" }
                if (leftTrim.length != rightTrim.length) {
                    return leftTrim.length - rightTrim.length
                }
                val numberCmp = leftTrim.compareTo(rightTrim)
                if (numberCmp != 0) {
                    return numberCmp
                }
                val zeroCmp = leftRun.length - rightRun.length
                if (zeroCmp != 0) {
                    return zeroCmp
                }
                continue
            }

            if (!leftIsDigit && !rightIsDigit) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < leftLength && !left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < rightLength && !right[rightIndex].isDigit()) rightIndex++
                val leftRun = left.substring(leftStart, leftIndex)
                val rightRun = right.substring(rightStart, rightIndex)
                val leftLower = leftRun.lowercase(locale)
                val rightLower = rightRun.lowercase(locale)
                val textCmp = leftLower.compareTo(rightLower)
                if (textCmp != 0) {
                    return textCmp
                }
                val caseCmp = leftRun.compareTo(rightRun)
                if (caseCmp != 0) {
                    return caseCmp
                }
                continue
            }

            val lowerCmp = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (lowerCmp != 0) {
                return lowerCmp
            }
            val caseCmp = leftChar.compareTo(rightChar)
            if (caseCmp != 0) {
                return caseCmp
            }
            leftIndex++
            rightIndex++
        }
        return leftLength - rightLength
    }
}
