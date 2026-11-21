// Keep your own package name at the top of MainActivity.kt:
package com.example.sgcarloan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Color Palette
private val BgSlate = Color(0xFFF8FAFC)
private val CardWhite = Color(0xFFFFFFFF)
private val BorderSlate = Color(0xFFE2E8F0)
private val TextPrimary = Color(0xFF0F172A)
private val TextSecondary = Color(0xFF475569)
private val TextMuted = Color(0xFF64748B)

// Option 2 (Car Loan) Theme Colors (Purple)
private val Purple600 = Color(0xFF563D7C)
private val Purple50 = Color(0xFFFAE6FF)
private val PurpleAccent = Color(0xFFBB6CF0)

// Option 1 (All-Cash) Theme Colors (Green)
private val Green600 = Color(0xFF15803D)
private val Green50 = Color(0xFFF0FDF4)

// Warning / Penalty Red Colors
private val Rose600 = Color(0xFFE11D48)

// Compatibility aliases
private val Emerald600 = Purple600
private val Blue600 = PurpleAccent
private val Blue50 = Purple50

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Emerald600,
                    background = BgSlate,
                    surface = CardWhite
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgSlate
                ) {
                    CarLoanSimulatorScreen()
                }
            }
        }
    }
}

// ============================================================================
// Financial Models & Calculation Engine
// ============================================================================

data class SimParams(
    val loanAmount: Double = 100_000.0,
    val carDiscount: Double = 5_000.0,
    val tenureYears: Int = 7,
    val flatRatePct: Double = 2.28,
    val settlementMonth: Int = 84,
    val oppCostPct: Double = 3.50,
    val discountRatePct: Double = 2.50,
    val earlyFeePct: Double = 1.0,
    val unearnedPenaltyPct: Double = 20.0
) {
    val tenureMonths: Int get() = tenureYears * 12
    val marginalTranche: Double get() = loanAmount + carDiscount
}

data class ScheduleRow(
    val month: Int,
    val installment: Double,
    val principalPaid: Double,
    val interestPaid: Double,
    val payoffLumpSum: Double,
    val poolYield: Double,
    val endingPoolBalance: Double
)

data class SimResult(
    val option1MarginalCost: Double,
    val option1Npv: Double,
    val monthlyPayment: Double,
    val totalContractualInterest: Double,
    val effectiveAnnualRatePct: Double,
    val earnedInterest: Double,
    val unearnedInterest: Double,
    val remainingPrincipal: Double,
    val unearnedInterestPenalty: Double,
    val earlyRedemptionFee: Double,
    val payoffLumpSum: Double,
    val grossLoanOutflow: Double,
    val totalOppYield: Double,
    val netNominalOption2Cost: Double,
    val terminalPoolSurplus: Double,
    val option2Npv: Double,
    val npvSavings: Double, // Positive = Option 2 (Loan) wins; Negative = Option 1 (Cash) wins
    val grossCostDiff: Double, // Option 1 - Option 2 Net Nominal
    val schedule: List<ScheduleRow>
)

fun calculateAnnualEirPct(principal: Double, monthlyPayment: Double, months: Int): Double {
    if (principal <= 0.0 || monthlyPayment <= 0.0 || months <= 0) return 0.0
    if (monthlyPayment * months <= principal) return 0.0

    var low = 1e-7
    var high = 1.0
    repeat(80) {
        val mid = (low + high) / 2.0
        val pv = monthlyPayment * (1.0 - (1.0 + mid).pow(-months)) / mid
        if (pv > principal) {
            low = mid
        } else {
            high = mid
        }
    }
    val monthlyRate = (low + high) / 2.0
    return ((1.0 + monthlyRate).pow(12) - 1.0) * 100.0
}

fun runSimulation(params: SimParams, overrideSettlementMonth: Int? = null): SimResult {
    val n = params.tenureMonths
    val k = (overrideSettlementMonth ?: params.settlementMonth).coerceIn(1, n)
    val l = params.loanAmount
    val d = params.carDiscount
    val marginalBase = l + d

    val totalInterest = l * (params.flatRatePct / 100.0) * (n / 12.0)
    val monthlyPayment = (l + totalInterest) / n
    val eirPct = calculateAnnualEirPct(l, monthlyPayment, n)

    val sumOfDigits = n * (n + 1) / 2.0
    val monthlyOppRate = (params.oppCostPct / 100.0) / 12.0
    val monthlyDiscRate = (params.discountRatePct / 100.0) / 12.0

    var earnedInterest = 0.0
    var principalRepaid = 0.0
    var poolBalance = marginalBase
    var totalYield = 0.0
    var totalInstallments = 0.0

    val rows = ArrayList<ScheduleRow>(k)

    for (t in 1..k) {
        val interestT = totalInterest * ((n - t + 1).toDouble() / sumOfDigits)
        val principalT = monthlyPayment - interestT
        earnedInterest += interestT
        principalRepaid += principalT
        totalInstallments += monthlyPayment

        val yieldT = poolBalance * monthlyOppRate
        totalYield += yieldT
        poolBalance = poolBalance + yieldT - monthlyPayment

        rows.add(
            ScheduleRow(
                month = t,
                installment = monthlyPayment,
                principalPaid = principalT,
                interestPaid = interestT,
                payoffLumpSum = 0.0,
                poolYield = yieldT,
                endingPoolBalance = poolBalance
            )
        )
    }

    val isEarly = k < n
    val remainingPrincipal = if (isEarly) max(0.0, l - principalRepaid) else 0.0
    val unearnedInterest = if (isEarly) max(0.0, totalInterest - earnedInterest) else 0.0
    val penaltyOnUnearned = if (isEarly) unearnedInterest * (params.unearnedPenaltyPct / 100.0) else 0.0
    val earlyFee = if (isEarly) l * (params.earlyFeePct / 100.0) else 0.0
    val payoffLumpSum = remainingPrincipal + penaltyOnUnearned + earlyFee

    // Deduct lump-sum settlement from retained cash pool at month k
    poolBalance -= payoffLumpSum
    if (rows.isNotEmpty()) {
        val last = rows.last()
        rows[rows.lastIndex] = last.copy(
            payoffLumpSum = payoffLumpSum,
            endingPoolBalance = poolBalance
        )
    }

    val grossLoanOutflow = totalInstallments + payoffLumpSum
    val netNominalOption2 = grossLoanOutflow - totalYield
    val npvSavings = poolBalance / (1.0 + monthlyDiscRate).pow(k)
    val option2Npv = marginalBase - npvSavings
    val grossCostDiff = marginalBase - netNominalOption2

    return SimResult(
        option1MarginalCost = marginalBase,
        option1Npv = marginalBase,
        monthlyPayment = monthlyPayment,
        totalContractualInterest = totalInterest,
        effectiveAnnualRatePct = eirPct,
        earnedInterest = earnedInterest,
        unearnedInterest = unearnedInterest,
        remainingPrincipal = remainingPrincipal,
        unearnedInterestPenalty = penaltyOnUnearned,
        earlyRedemptionFee = earlyFee,
        payoffLumpSum = payoffLumpSum,
        grossLoanOutflow = grossLoanOutflow,
        totalOppYield = totalYield,
        netNominalOption2Cost = netNominalOption2,
        terminalPoolSurplus = poolBalance,
        option2Npv = option2Npv,
        npvSavings = npvSavings,
        grossCostDiff = grossCostDiff,
        schedule = rows
    )
}

fun formatCurrency(value: Double, decimals: Int = 0): String {
    val nf = NumberFormat.getCurrencyInstance(Locale.US).apply {
        maximumFractionDigits = decimals
        minimumFractionDigits = decimals
    }
    return nf.format(value)
}

fun formatSignedCurrency(value: Double): String {
    val prefix = if (value > 0.005) "+" else if (value < -0.005) "-" else ""
    return "$prefix${formatCurrency(abs(value))}"
}

// ============================================================================
// Main UI Screen
// ============================================================================

@Composable
fun CarLoanSimulatorScreen() {
    var params by remember { mutableStateOf(SimParams()) }
    var showPenaltySettings by remember { mutableStateOf(false) }
    var showFullSchedule by remember { mutableStateOf(false) }

    val result by remember(params) { derivedStateOf { runSimulation(params) } }

    // Precompute NPV savings curve across all settlement months 1..N
    val npvTrajectory by remember(
        params.loanAmount,
        params.carDiscount,
        params.tenureYears,
        params.flatRatePct,
        params.oppCostPct,
        params.discountRatePct,
        params.earlyFeePct,
        params.unearnedPenaltyPct
    ) {
        derivedStateOf {
            (1..params.tenureMonths).map { month ->
                runSimulation(params, overrideSettlementMonth = month).npvSavings
            }
        }
    }

    val isFullTenurePreset = params.settlementMonth == params.tenureMonths
    val isSettle12Preset = params.settlementMonth == 12 && !isFullTenurePreset

    Scaffold(
        containerColor = BgSlate
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SG Car Loan vs. All-Cash",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "NPV Comparator",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Emerald600
                    )
                    Text(
                        text = "Marginal Basis (${formatCurrency(params.marginalTranche)}) • Rule of 78 • Zero Double-Counting",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Preset Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetButton(
                        label = "Reset Default",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { params = SimParams() }
                    )
                    PresetButton(
                        label = "Settle 12 Mo",
                        selected = isSettle12Preset,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            params = params.copy(
                                settlementMonth = min(12, params.tenureMonths)
                            )
                        }
                    )
                    PresetButton(
                        label = "Full Tenure",
                        selected = isFullTenurePreset,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            params = params.copy(settlementMonth = params.tenureMonths)
                        }
                    )
                }
            }

            // Verdict Banner
            item {
                VerdictBanner(params = params, result = result)
            }

            // Key Summary Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "Option 1 (All-Cash) NPV",
                        value = formatCurrency(result.option1Npv),
                        subtitle = "Upfront L + D outlay",
                        valueColor = Green600,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Option 2 (Loan) NPV",
                        value = formatCurrency(result.option2Npv),
                        subtitle = "Settled at Mo ${params.settlementMonth}",
                        valueColor = Purple600,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        title = "Monthly Installment",
                        value = formatCurrency(result.monthlyPayment),
                        subtitle = "Mo 1: P ${formatCurrency(result.schedule.first().principalPaid, 0)} / I ${formatCurrency(result.schedule.first().interestPaid, 0)}",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Gross Cost Diff",
                        value = formatSignedCurrency(result.grossCostDiff),
                        subtitle = if (result.grossCostDiff >= 0) "Loan net nominal cheaper" else "Cash net nominal cheaper",
                        valueColor = if (result.grossCostDiff >= 0) Emerald600 else Rose600,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Simulation Parameters Card
            item {
                SectionCard(title = "Simulation Parameters") {
                    // Loan Amount ($10k - $250k)
                    AlignedParameterSlider(
                        label = "Loan Amount",
                        formattedValue = formatCurrency(params.loanAmount, 0),
                        value = params.loanAmount.toFloat(),
                        valueRange = 10_000f..250_000f,
                        step = 5_000f,
                        ticks = listOf(
                            10_000f to "$10k",
                            100_000f to "$100k",
                            175_000f to "$175k",
                            250_000f to "$250k"
                        ),
                        onValueChange = { params = params.copy(loanAmount = it.toDouble()) }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Upfront Car Discount ($0 - $20k)
                    AlignedParameterSlider(
                        label = "Upfront Car Discount",
                        formattedValue = formatCurrency(params.carDiscount, 0),
                        value = params.carDiscount.toFloat(),
                        valueRange = 0f..20_000f,
                        step = 500f,
                        ticks = listOf(
                            0f to "$0",
                            5_000f to "$5k",
                            10_000f to "$10k",
                            20_000f to "$20k"
                        ),
                        onValueChange = { params = params.copy(carDiscount = it.toDouble()) }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Loan Tenure (1 - 7 years)
                    AlignedParameterSlider(
                        label = "Loan Tenure",
                        formattedValue = "${params.tenureYears} yrs (${params.tenureMonths} mo)",
                        value = params.tenureYears.toFloat(),
                        valueRange = 1f..7f,
                        step = 1f,
                        ticks = listOf(
                            1f to "1y",
                            3f to "3y",
                            5f to "5y",
                            7f to "7y"
                        ),
                        onValueChange = { newYears ->
                            val yearsInt = newYears.roundToInt()
                            val newMaxMonths = yearsInt * 12
                            params = params.copy(
                                tenureYears = yearsInt,
                                settlementMonth = min(params.settlementMonth, newMaxMonths)
                            )
                        }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Flat Interest Rate + Live EIR Badge
                    AlignedParameterSlider(
                        label = "Loan Flat Interest Rate",
                        formattedValue = String.format(Locale.US, "%.2f%%", params.flatRatePct),
                        badgeText = String.format(Locale.US, "EIR: %.2f%% p.a.", result.effectiveAnnualRatePct),
                        value = params.flatRatePct.toFloat(),
                        valueRange = 1.0f..5.0f,
                        step = 0.02f,
                        ticks = listOf(
                            1.0f to "1.0%",
                            2.28f to "2.28%",
                            3.5f to "3.5%",
                            5.0f to "5.0%"
                        ),
                        onValueChange = {
                            val rounded = (it * 100.0).roundToInt() / 100.0
                            params = params.copy(flatRatePct = rounded)
                        }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Early Settlement Month (1..N)
                    val maxMo = params.tenureMonths.toFloat()
                    val midMo = (params.tenureMonths / 2).toFloat()
                    val settlementTicks = buildList {
                        add(1f to "Mo 1")
                        if (params.tenureMonths > 12) add(12f to "Mo 12")
                        if (params.tenureMonths >= 36) add(midMo to "Mo ${midMo.toInt()}")
                        add(maxMo to "Mo ${params.tenureMonths}")
                    }
                    AlignedParameterSlider(
                        label = "Early Settlement Month",
                        formattedValue = if (params.settlementMonth == params.tenureMonths)
                            "Mo ${params.settlementMonth} (Full)"
                        else
                            "Month ${params.settlementMonth}",
                        value = params.settlementMonth.toFloat(),
                        valueRange = 1f..maxMo,
                        step = 1f,
                        ticks = settlementTicks,
                        onValueChange = {
                            params = params.copy(settlementMonth = it.roundToInt())
                        }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Opportunity Cost Rate
                    AlignedParameterSlider(
                        label = "Opportunity Cost (p.a.)",
                        formattedValue = String.format(Locale.US, "%.2f%%", params.oppCostPct),
                        value = params.oppCostPct.toFloat(),
                        valueRange = 0.0f..10.0f,
                        step = 0.1f,
                        ticks = listOf(
                            0f to "0%",
                            2.5f to "2.5%",
                            5.0f to "5%",
                            10.0f to "10%"
                        ),
                        onValueChange = {
                            val rounded = (it * 100.0).roundToInt() / 100.0
                            params = params.copy(oppCostPct = rounded)
                        }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Discount Rate
                    AlignedParameterSlider(
                        label = "Discount Rate (p.a.)",
                        formattedValue = String.format(Locale.US, "%.2f%%", params.discountRatePct),
                        value = params.discountRatePct.toFloat(),
                        valueRange = 0.0f..10.0f,
                        step = 0.1f,
                        ticks = listOf(
                            0f to "0%",
                            2.5f to "2.5%",
                            5.0f to "5%",
                            10.0f to "10%"
                        ),
                        onValueChange = {
                            val rounded = (it * 100.0).roundToInt() / 100.0
                            params = params.copy(discountRatePct = rounded)
                        }
                    )

                    HorizontalDivider(color = BorderSlate)

                    // Collapsible Bank Penalty Assumptions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPenaltySettings = !showPenaltySettings }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bank Penalty Assumptions",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Text(
                            text = if (showPenaltySettings) "Hide ▲" else "Edit ▼",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Blue600
                        )
                    }

                    if (showPenaltySettings) {
                        Spacer(modifier = Modifier.height(6.dp))
                        AlignedParameterSlider(
                            label = "Early Repayment Fee (% of Loan)",
                            formattedValue = String.format(Locale.US, "%.1f%%", params.earlyFeePct),
                            value = params.earlyFeePct.toFloat(),
                            valueRange = 0f..3f,
                            step = 0.1f,
                            ticks = listOf(0f to "0%", 1f to "1%", 2f to "2%", 3f to "3%"),
                            onValueChange = {
                                val rounded = (it * 10.0).roundToInt() / 10.0
                                params = params.copy(earlyFeePct = rounded)
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        AlignedParameterSlider(
                            label = "Unearned Interest Penalty",
                            formattedValue = String.format(Locale.US, "%.0f%%", params.unearnedPenaltyPct),
                            value = params.unearnedPenaltyPct.toFloat(),
                            valueRange = 0f..30f,
                            step = 5f,
                            ticks = listOf(0f to "0%", 10f to "10%", 20f to "20%", 30f to "30%"),
                            onValueChange = {
                                params = params.copy(unearnedPenaltyPct = it.roundToInt().toDouble())
                            }
                        )
                    }
                }
            }

            // Interactive NPV Trajectory Chart
            item {
                SectionCard(title = "NPV Savings by Settlement Month (Tap to Select)") {
                    NpvTrajectoryChart(
                        values = npvTrajectory,
                        selectedMonth = params.settlementMonth,
                        onSelectMonth = { month ->
                            params = params.copy(settlementMonth = month)
                        }
                    )
                }
            }

            // Financial Reconciliation Table
            item {
                SectionCard(title = "Financial Reconciliation (Marginal Basis)") {
                    ReconciliationTable(params = params, result = result)
                }
            }

            // Rule of 78 Monthly Amortization & Cash Pool Table
            item {
                SectionCard(title = "Rule of 78 Schedule (Months 1–${params.settlementMonth})") {
                    val displayedRows = if (showFullSchedule || result.schedule.size <= 12) {
                        result.schedule
                    } else {
                        result.schedule.take(12)
                    }

                    AmortizationTable(rows = displayedRows)

                    if (result.schedule.size > 12) {
                        TextButton(
                            onClick = { showFullSchedule = !showFullSchedule },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = if (showFullSchedule)
                                    "Show First 12 Months"
                                else
                                    "Show All ${result.schedule.size} Months",
                                color = Blue600,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// UI Subcomponents
// ============================================================================

@Composable
fun PresetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Emerald600 else CardWhite,
            contentColor = if (selected) Color.White else TextSecondary
        ),
        border = BorderStroke(1.dp, if (selected) Emerald600 else BorderSlate)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun VerdictBanner(params: SimParams, result: SimResult) {
    val loanWins = result.npvSavings >= 0
    val bgColor = if (loanWins) Purple50 else Green50
    val accentColor = if (loanWins) Purple600 else Green600

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (loanWins)
                    "Option 2 (Car Loan) Wins by ${formatCurrency(result.npvSavings)} NPV"
                else
                    "Option 1 (All-Cash) Wins by ${formatCurrency(abs(result.npvSavings))} NPV",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = if (params.settlementMonth == params.tenureMonths)
                    "Held to full ${params.tenureMonths}-month tenure (early termination penalties waived)."
                else
                    "Settled at Month ${params.settlementMonth} with ${formatCurrency(result.payoffLumpSum)} lump-sum payoff (includes ${formatCurrency(result.unearnedInterestPenalty + result.earlyRedemptionFee)} penalties).",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(1.dp, BorderSlate),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
            Text(text = value, fontSize = 17.sp, color = valueColor, fontWeight = FontWeight.Bold)
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(1.dp, BorderSlate),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            content()
        }
    }
}

@Composable
fun AlignedParameterSlider(
    label: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    ticks: List<Pair<Float, String>>,
    badgeText: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                if (badgeText != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Blue50)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Blue600
                        )
                    }
                }
            }
            Text(
                text = formattedValue,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Emerald600
            )
        }

        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { raw ->
                val snapped = (raw / step).roundToInt() * step
                onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Emerald600,
                activeTrackColor = Emerald600,
                inactiveTrackColor = BorderSlate
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Geometrically aligned tick scale matching Material 3 Slider thumb inset (~10.dp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
        ) {
            val thumbInset = 10.dp
            val labelWidth = 50.dp
            val usableWidth = maxWidth - thumbInset * 2
            val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)

            ticks.forEach { (tickVal, tickLabel) ->
                val fraction = ((tickVal - valueRange.start) / span).coerceIn(0f, 1f)
                val xPos = thumbInset + usableWidth * fraction
                val alignOffset = when {
                    fraction <= 0.01f -> 0.dp
                    fraction >= 0.99f -> -labelWidth
                    else -> -labelWidth / 2
                }
                val textAlign = when {
                    fraction <= 0.01f -> TextAlign.Start
                    fraction >= 0.99f -> TextAlign.End
                    else -> TextAlign.Center
                }
                Text(
                    text = tickLabel,
                    fontSize = 10.sp,
                    color = TextMuted,
                    textAlign = textAlign,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .width(labelWidth)
                        .offset(x = (xPos + alignOffset).coerceIn(0.dp, maxWidth - labelWidth))
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
fun NpvTrajectoryChart(
    values: List<Double>,
    selectedMonth: Int,
    onSelectMonth: (Int) -> Unit
) {
    if (values.isEmpty()) return

    val maxVal = max(values.maxOrNull() ?: 0.0, 0.0)
    val minVal = min(values.minOrNull() ?: 0.0, 0.0)
    val range = max(maxVal - minVal, 100.0)
    val paddedMax = maxVal + range * 0.12
    val paddedMin = minVal - range * 0.12
    val totalSpan = paddedMax - paddedMin

    val selectedVal = values[(selectedMonth - 1).coerceIn(0, values.lastIndex)]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Selected: Month $selectedMonth",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Text(
                text = "NPV Savings: ${formatSignedCurrency(selectedVal)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selectedVal >= 0) Emerald600 else Rose600
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSlate)
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        val padX = 24f
                        val chartW = (size.width - padX * 2).coerceAtLeast(1f)
                        val frac = ((offset.x - padX) / chartW).coerceIn(0f, 1f)
                        val month = (frac * (values.size - 1)).roundToInt() + 1
                        onSelectMonth(month.coerceIn(1, values.size))
                    }
                }
        ) {
            val padX = 24f
            val padY = 18f
            val chartW = size.width - padX * 2
            val chartH = size.height - padY * 2

            fun yFor(v: Double): Float {
                return padY + ((paddedMax - v) / totalSpan).toFloat() * chartH
            }

            fun xFor(index: Int): Float {
                val denom = (values.size - 1).coerceAtLeast(1)
                return padX + (index.toFloat() / denom) * chartW
            }

            // Zero breakeven dashed line
            val zeroY = yFor(0.0)
            drawLine(
                color = TextMuted.copy(alpha = 0.5f),
                start = Offset(padX, zeroY),
                end = Offset(size.width - padX, zeroY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            )

            // Curve path
            val path = Path()
            values.forEachIndexed { idx, v ->
                val x = xFor(idx)
                val y = yFor(v)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Blue600,
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )

            // Selected month vertical guide and highlight dot
            val selIdx = (selectedMonth - 1).coerceIn(0, values.lastIndex)
            val selX = xFor(selIdx)
            val selY = yFor(values[selIdx])
            val dotColor = if (values[selIdx] >= 0) Emerald600 else Rose600

            drawLine(
                color = dotColor.copy(alpha = 0.35f),
                start = Offset(selX, padY),
                end = Offset(selX, size.height - padY),
                strokeWidth = 3f
            )
            drawCircle(color = Color.White, radius = 12f, center = Offset(selX, selY))
            drawCircle(color = dotColor, radius = 8f, center = Offset(selX, selY))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Month 1", fontSize = 10.sp, color = TextMuted)
            Text("Above dashed line = Loan wins", fontSize = 10.sp, color = TextMuted)
            Text("Month ${values.size}", fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
fun ReconciliationTable(params: SimParams, result: SimResult) {
    val rows = listOf(
        Triple("Upfront Marginal Outlay", formatCurrency(result.option1MarginalCost), "$0"),
        Triple("Monthly Installments (×${params.settlementMonth})", "$0", formatCurrency(result.monthlyPayment * params.settlementMonth)),
        Triple("Remaining Principal Payoff", "$0", formatCurrency(result.remainingPrincipal)),
        Triple("Unearned Interest Penalty", "$0", formatCurrency(result.unearnedInterestPenalty)),
        Triple("Early Redemption Fee", "$0", formatCurrency(result.earlyRedemptionFee)),
        Triple("Gross Nominal Outflow", formatCurrency(result.option1MarginalCost), formatCurrency(result.grossLoanOutflow)),
        Triple("Less: Retained Pool Yield", "$0", "-${formatCurrency(result.totalOppYield)}"),
        Triple("Net Nominal Cost", formatCurrency(result.option1MarginalCost), formatCurrency(result.netNominalOption2Cost)),
        Triple("Net Present Value (NPV)", formatCurrency(result.option1Npv), formatCurrency(result.option2Npv))
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Line Item", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, modifier = Modifier.weight(1.5f))
            Text("Opt 1 (Cash)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Opt 2 (Loan)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = BorderSlate)

        rows.forEachIndexed { idx, (label, opt1, opt2) ->
            val isTotal = idx == rows.lastIndex || idx == 7
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isTotal) BgSlate else Color.Transparent)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                    color = TextPrimary,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = opt1,
                    fontSize = 12.sp,
                    fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                    color = if (idx == rows.lastIndex) Green600 else TextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = opt2,
                    fontSize = 12.sp,
                    fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                    color = if (idx == rows.lastIndex) Purple600 else TextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun AmortizationTable(rows: List<ScheduleRow>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.padding(vertical = 4.dp)) {
            TableCell("Mo", 40.dp, FontWeight.Bold, TextMuted, TextAlign.Start)
            TableCell("Payment", 82.dp, FontWeight.Bold, TextMuted)
            TableCell("Principal", 82.dp, FontWeight.Bold, TextMuted)
            TableCell("Interest", 76.dp, FontWeight.Bold, TextMuted)
            TableCell("Payoff", 86.dp, FontWeight.Bold, TextMuted)
            TableCell("Pool Yield", 76.dp, FontWeight.Bold, TextMuted)
            TableCell("Pool Bal", 92.dp, FontWeight.Bold, TextMuted)
        }
        HorizontalDivider(color = BorderSlate)

        rows.forEach { r ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                TableCell("${r.month}", 40.dp, FontWeight.SemiBold, TextPrimary, TextAlign.Start)
                TableCell(formatCurrency(r.installment), 82.dp, FontWeight.Normal, TextPrimary)
                TableCell(formatCurrency(r.principalPaid), 82.dp, FontWeight.Normal, TextPrimary)
                TableCell(formatCurrency(r.interestPaid), 76.dp, FontWeight.Normal, TextSecondary)
                TableCell(if (r.payoffLumpSum > 0) formatCurrency(r.payoffLumpSum) else "—", 86.dp, FontWeight.Normal, if (r.payoffLumpSum > 0) Rose600 else TextMuted)
                TableCell(formatCurrency(r.poolYield), 76.dp, FontWeight.Normal, Emerald600)
                TableCell(formatCurrency(r.endingPoolBalance), 92.dp, FontWeight.Medium, TextPrimary)
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    weight: FontWeight,
    color: Color,
    align: TextAlign = TextAlign.End
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = weight,
        color = color,
        textAlign = align,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 3.dp)
    )
}