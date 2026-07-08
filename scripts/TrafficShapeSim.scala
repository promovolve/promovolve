//> using scala 3.3.3

import java.time.{DayOfWeek, LocalDate}

/**
 * Traffic Shape Learning Simulator
 *
 * Simulates traffic patterns and visualizes how the tracker learns shapes.
 * Demonstrates weekday vs weekend pattern separation.
 */
object TrafficShapeSim {

  // Simulated TrafficShapeTracker (simplified version for standalone use)
  class TrafficShapeTracker(val bucketCount: Int = 24) {
    private val weekdayShape: Array[Double] = Array.fill(bucketCount)(1.0)
    private val weekendShape: Array[Double] = Array.fill(bucketCount)(1.0)
    private val todayCount: Array[Long] = Array.fill(bucketCount)(0L)
    private var weekdayTotal: Double = bucketCount.toDouble
    private var weekendTotal: Double = bucketCount.toDouble
    private var todayIsWeekend: Boolean = false

    def setDayType(isWeekend: Boolean): Unit = todayIsWeekend = isWeekend
    def isTrackingWeekend: Boolean = todayIsWeekend

    def recordRequest(elapsedSeconds: Double): Unit = {
      val bucket = math.min((elapsedSeconds / 3600).toInt, bucketCount - 1)
      todayCount(bucket) += 1
    }

    def rolloverDay(dayAlpha: Double = 0.2): Unit = {
      val todayTotal = todayCount.sum
      if (todayTotal <= 0) {
        java.util.Arrays.fill(todayCount, 0L)
        return
      }

      val avgCount = todayTotal.toDouble / bucketCount
      val todayNormalized = todayCount.map(c => c.toDouble / avgCount)

      val shape = if (todayIsWeekend) weekendShape else weekdayShape
      var newTotal = 0.0
      var i = 0
      while (i < bucketCount) {
        shape(i) = dayAlpha * todayNormalized(i) + (1 - dayAlpha) * shape(i)
        newTotal += shape(i)
        i += 1
      }

      if (todayIsWeekend) weekendTotal = newTotal else weekdayTotal = newTotal
      java.util.Arrays.fill(todayCount, 0L)
    }

    def weekdayVolumes: Array[Double] = weekdayShape.clone()
    def weekendVolumes: Array[Double] = weekendShape.clone()
    def todayCounts: Array[Long] = todayCount.clone()

    def currentShape: Array[Double] = if (todayIsWeekend) weekendShape else weekdayShape
    def cachedTotal: Double = if (todayIsWeekend) weekendTotal else weekdayTotal
  }

  // Traffic pattern generators
  trait TrafficPattern {
    def requestsPerHour(hour: Int): Int
  }

  // Typical weekday: morning commute peak, lunch dip, afternoon peak, evening decline
  case class WeekdayPattern(baseRate: Int = 100) extends TrafficPattern {
    private val shape = Array(
      0.2, 0.1, 0.1, 0.1, 0.2, 0.4,  // 0-5: night/early morning
      0.8, 1.5, 2.0, 1.8, 1.5, 1.2,  // 6-11: morning commute + work
      1.0, 1.3, 1.5, 1.8, 2.0, 2.2,  // 12-17: lunch + afternoon
      2.5, 2.0, 1.5, 1.0, 0.6, 0.3   // 18-23: evening peak + decline
    )
    def requestsPerHour(hour: Int): Int = (baseRate * shape(hour)).toInt
  }

  // Weekend: late start, steady daytime, evening peak
  case class WeekendPattern(baseRate: Int = 80) extends TrafficPattern {
    private val shape = Array(
      0.3, 0.2, 0.1, 0.1, 0.1, 0.2,  // 0-5: night
      0.3, 0.5, 0.8, 1.2, 1.5, 1.8,  // 6-11: slow morning
      2.0, 2.2, 2.3, 2.2, 2.0, 1.8,  // 12-17: steady afternoon
      2.0, 2.5, 2.8, 2.0, 1.2, 0.5   // 18-23: evening peak + decline
    )
    def requestsPerHour(hour: Int): Int = (baseRate * shape(hour)).toInt
  }

  // Simulate one day of traffic
  def simulateDay(tracker: TrafficShapeTracker, pattern: TrafficPattern, dayOfWeek: DayOfWeek): Unit = {
    val isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
    tracker.setDayType(isWeekend)

    for (hour <- 0 until 24) {
      val requests = pattern.requestsPerHour(hour)
      // Spread requests across the hour
      for (_ <- 0 until requests) {
        val elapsedSeconds = hour * 3600 + scala.util.Random.nextInt(3600)
        tracker.recordRequest(elapsedSeconds.toDouble)
      }
    }
  }

  // ASCII histogram visualization
  def visualizeShape(shape: Array[Double], title: String, width: Int = 40): Unit = {
    val maxVal = shape.max
    val minVal = shape.min
    val range = if (maxVal > minVal) maxVal - minVal else 1.0

    println(s"\n$title")
    println("=" * (width + 10))
    val header = "Shape".padTo(width, ' ')
    println(s"Hour | $header | Value")
    println("-" * (width + 18))

    for ((vol, hour) <- shape.zipWithIndex) {
      val normalized = if (range > 0) (vol - minVal) / range else 0.5
      val barLen = (normalized * width).toInt
      val bar = "█" * barLen + "░" * (width - barLen)
      println(f"  $hour%02d | $bar | $vol%.2f")
    }
    println()
  }

  // Compact shape display
  def compactShape(shape: Array[Double]): String = {
    val bars = "▁▂▃▄▅▆▇█"
    val maxVal = shape.max
    shape.map { v =>
      val idx = if (maxVal > 0) ((v / maxVal) * (bars.length - 1)).toInt else 0
      bars(math.max(0, math.min(idx, bars.length - 1)))
    }.mkString
  }

  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("Traffic Shape Learning Simulator")
    println("=" * 60)

    val tracker = new TrafficShapeTracker(bucketCount = 24)
    val weekdayPattern = WeekdayPattern(baseRate = 100)
    val weekendPattern = WeekendPattern(baseRate = 80)

    // Simulate multiple weeks
    val weeks = 4
    val startDate = LocalDate.of(2024, 1, 1) // A Monday

    println(s"\nSimulating $weeks weeks of traffic...")
    println("Weekday pattern: morning/evening peaks")
    println("Weekend pattern: afternoon/evening peaks")
    println()

    for (week <- 1 to weeks) {
      print(s"Week $week: ")
      for (dayOffset <- 0 until 7) {
        val date = startDate.plusDays((week - 1) * 7 + dayOffset)
        val dayOfWeek = date.getDayOfWeek
        val isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
        val pattern = if (isWeekend) weekendPattern else weekdayPattern

        simulateDay(tracker, pattern, dayOfWeek)
        tracker.rolloverDay(0.2) // 20% today, 80% historical

        val marker = dayOfWeek.toString.take(2)
        print(s"$marker ")
      }

      // Show progress
      println()
      println(s"  Weekday: ${compactShape(tracker.weekdayVolumes)}")
      println(s"  Weekend: ${compactShape(tracker.weekendVolumes)}")
    }

    // Final visualization
    visualizeShape(tracker.weekdayVolumes, "LEARNED WEEKDAY SHAPE (Mon-Fri)")
    visualizeShape(tracker.weekendVolumes, "LEARNED WEEKEND SHAPE (Sat-Sun)")

    // Show actual vs learned comparison
    println("\n" + "=" * 60)
    println("PATTERN COMPARISON")
    println("=" * 60)

    println("\nWeekday - Actual traffic pattern:")
    val actualWeekday = (0 until 24).map(h => weekdayPattern.requestsPerHour(h).toDouble)
    val avgWeekday = actualWeekday.sum / 24
    val normalizedActualWeekday = actualWeekday.map(_ / avgWeekday).toArray
    println(s"  ${compactShape(normalizedActualWeekday)}")

    println("\nWeekday - Learned shape:")
    println(s"  ${compactShape(tracker.weekdayVolumes)}")

    println("\nWeekend - Actual traffic pattern:")
    val actualWeekend = (0 until 24).map(h => weekendPattern.requestsPerHour(h).toDouble)
    val avgWeekend = actualWeekend.sum / 24
    val normalizedActualWeekend = actualWeekend.map(_ / avgWeekend).toArray
    println(s"  ${compactShape(normalizedActualWeekend)}")

    println("\nWeekend - Learned shape:")
    println(s"  ${compactShape(tracker.weekendVolumes)}")

    // Calculate correlation/fit
    def correlation(a: Array[Double], b: Array[Double]): Double = {
      val avgA = a.sum / a.length
      val avgB = b.sum / b.length
      val numerator = a.zip(b).map { case (ai, bi) => (ai - avgA) * (bi - avgB) }.sum
      val denomA = math.sqrt(a.map(ai => (ai - avgA) * (ai - avgA)).sum)
      val denomB = math.sqrt(b.map(bi => (bi - avgB) * (bi - avgB)).sum)
      if (denomA > 0 && denomB > 0) numerator / (denomA * denomB) else 0.0
    }

    val weekdayCorr = correlation(normalizedActualWeekday, tracker.weekdayVolumes)
    val weekendCorr = correlation(normalizedActualWeekend, tracker.weekendVolumes)

    println(s"\nWeekday shape correlation: ${(weekdayCorr * 100).toInt}%")
    println(s"Weekend shape correlation: ${(weekendCorr * 100).toInt}%")

    // Summary stats
    println("\n" + "=" * 60)
    println("SHAPE STATISTICS")
    println("=" * 60)

    def stats(shape: Array[Double], name: String): Unit = {
      val max = shape.max
      val min = shape.min
      val maxHour = shape.zipWithIndex.maxBy(_._1)._2
      val minHour = shape.zipWithIndex.minBy(_._1)._2
      val peakRatio = if (min > 0) max / min else 0.0
      println(f"\n$name:")
      println(f"  Peak hour: $maxHour%02d:00 (value: $max%.2f)")
      println(f"  Valley hour: $minHour%02d:00 (value: $min%.2f)")
      println(f"  Peak/Valley ratio: $peakRatio%.1fx")
    }

    stats(tracker.weekdayVolumes, "Weekday Shape")
    stats(tracker.weekendVolumes, "Weekend Shape")

    // Output JSON for RunScenario
    println("\n" + "=" * 60)
    println("JSON OUTPUT (for RunScenario or PacingConfig)")
    println("=" * 60)

    def toJson(arr: Array[Double]): String = arr.map(v => f"$v%.3f").mkString("[", ", ", "]")

    println(s"""
{
  "weekdayShapeVolumes": ${toJson(tracker.weekdayVolumes)},
  "weekendShapeVolumes": ${toJson(tracker.weekendVolumes)}
}
""")
  }
}
