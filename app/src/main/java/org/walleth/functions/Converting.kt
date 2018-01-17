package org.walleth.functions

import org.walleth.data.tokens.Token
import org.walleth.khex.prepend0xPrefix
import java.math.BigDecimal
import java.math.BigInteger
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.*

fun BigInteger.toHexString() = this.toString(16).prepend0xPrefix()

fun String.extractValueForToken(token: Token) = BigDecimal(this).multiply(token.decimalsAsMultiplicator()).toBigInteger()

// ENGLISH is used until Android O becomes the minSDK or the support-lib fixes this problem:
// https://stackoverflow.com/questions/3821539/decimal-separator-comma-with-numberdecimal-inputtype-in-edittext
val inputDecimalFormat = (NumberFormat.getInstance(Locale.ENGLISH) as DecimalFormat).apply {
    isParseBigDecimal = true
}

val decimalSymbols = DecimalFormatSymbols(Locale.ENGLISH).apply { decimalSeparator = '.' }

private fun getDecimalFormatUS(): DecimalFormat = NumberFormat.getInstance(Locale.US) as DecimalFormat

val decimalFormat = getDecimalFormatUS().apply {
    isGroupingUsed = false
}
val sixDigitDecimalFormat = getDecimalFormat(6)
val twoDigitDecimalFormat = getDecimalFormat(2)

fun String.replaceNullDecimals(decimals : Int) = replace("."+"0".repeat(decimals),"")


private fun getDecimalFormat(decimals: Int) = getDecimalFormatUS().apply {
    applyPattern("#0." + "0".repeat(decimals))
    isGroupingUsed = false
}

fun String.asBigDecimal() = inputDecimalFormat.parseObject(this) as BigDecimal