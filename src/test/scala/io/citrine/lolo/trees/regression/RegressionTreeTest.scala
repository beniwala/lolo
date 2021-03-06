package io.citrine.lolo.trees.regression

import java.io.{File, FileOutputStream, ObjectOutputStream}

import io.citrine.lolo.TestUtils
import io.citrine.lolo.linear.LinearRegressionLearner
import io.citrine.lolo.stats.functions.Friedman
import org.junit.Test
import org.scalatest.Assertions._

import scala.util.Random

/**
  * Created by maxhutch on 11/29/16.
  */
@Test
class RegressionTreeTest {

  /**
    * Trivial models with no splits should have finite feature importance.
    */
  @Test
  def testFeatureImportanceNaN(): Unit = {
    val X = Vector.fill(100) {
      val input = Vector.fill(10)(1.0)
      (input, 2.0)
    }

    val DTLearner = RegressionTreeLearner()
    val DTMeta = DTLearner.train(X)
    val DT = DTMeta.getModel()
    assert(DTMeta.getFeatureImportance()
      .get.forall(v => !v.isNaN))
  }

  /**
    * Test a simple tree with only real inputs
    */
  @Test
  def testSimpleTree(): Unit = {
    val csv = TestUtils.readCsv("double_example.csv")
    val trainingData = csv.map(vec => (vec.init, vec.last.asInstanceOf[Double]))
    val DTLearner = RegressionTreeLearner()
    val DT = DTLearner.train(trainingData).getModel()

    /* We should be able to memorize the inputs */
    val output = DT.transform(trainingData.map(_._1))
    trainingData.zip(output.getExpected()).foreach { case ((x, a), p) =>
      assert(Math.abs(a - p) < 1.0e-9)
    }
    assert(output.getGradient().isEmpty)
    output.getDepth().foreach(d => assert(d > 3 && d < 9, s"Depth is ${d}"))
  }

  /**
    * Test a larger case and time it as a benchmark guideline
    */
  @Test
  def longerTest(): Unit = {
    val trainingData = TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman)
    val DTLearner = RegressionTreeLearner()
    val N = 100
    val start = System.nanoTime()
    val DTMeta = DTLearner.train(trainingData)
    val DT = DTMeta.getModel()
    (0 until N).map(i => DTLearner.train(trainingData).getModel())
    val duration = (System.nanoTime() - start) / 1.0e9

    println(s"Training large case took ${duration / N} s")

    /* We should be able to memorize the inputs */
    val output = DT.transform(trainingData.map(_._1))
    trainingData.zip(output.getExpected()).foreach { case ((x, a), p) =>
      assert(Math.abs(a - p) < 1.0e-9)
    }
    assert(output.getGradient().isEmpty)
    output.getDepth().foreach(d => assert(d > 4 && d < 20, s"Depth is ${d}"))

    /* The first feature should be the most important */
    val importances = DTMeta.getFeatureImportance().get
    assert(importances(1) == importances.max)

    val tmpFile: File = File.createTempFile("tmp", ".csv")
    val oos = new ObjectOutputStream(new FileOutputStream(tmpFile))
    oos.writeObject(DT)
  }

  /**
    * Train with a categorical variable
    */
  @Test
  def testCategorical(): Unit = {
    val trainingData = TestUtils.binTrainingData(
      TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman),
      inputBins = Seq((0, 8))
    ).asInstanceOf[Seq[(Vector[Any], Double)]]

    val DTLearner = RegressionTreeLearner()
    val N = 100
    val start = System.nanoTime()
    val DTMeta = DTLearner.train(trainingData)
    val DT = DTMeta.getModel()
    (0 until N).map(i => DTLearner.train(trainingData).getModel())
    val duration = (System.nanoTime() - start) / 1.0e9

    println(s"Training large case took ${duration / N} s")

    /* We should be able to memorize the inputs */
    val output = DT.transform(trainingData.map(_._1))
    trainingData.zip(output.getExpected()).foreach { case ((x, a), p) =>
      assert(Math.abs(a - p) < 1.0e-9)
    }
    assert(output.getGradient().isEmpty)
    output.getDepth().foreach(d => assert(d > 4 && d < 21, s"Depth is ${d}"))

    /* The first feature should be the most important */
    val importances = DTMeta.getFeatureImportance().get
    assert(importances(1) == importances.max)
    val tmpFile: File = File.createTempFile("tmp", ".csv")
    val oos = new ObjectOutputStream(new FileOutputStream(tmpFile))
    oos.writeObject(DT)
  }

  /**
    * Train with linear leaves.  This case is under-constriained, so we should hit the results exactly
    */
  @Test
  def testLinearLeaves(): Unit = {
    val trainingData = TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman)

    val linearLearner = LinearRegressionLearner(regParam = Some(0.0))
    val DTLearner = RegressionTreeLearner(leafLearner = Some(linearLearner), minLeafInstances = 2)
    val DTMeta = DTLearner.train(trainingData)
    val DT = DTMeta.getModel()

    /* We should be able to memorize the inputs */
    val output = DT.transform(trainingData.map(_._1))
    trainingData.zip(output.getExpected()).foreach { case ((x, a), p) =>
      assert(Math.abs(a - p) < 1.0e-9)
    }
    assert(output.getGradient().isDefined)
    output.getDepth().foreach(d => assert(d > 4 && d < 18, s"Depth is ${d}"))

    /* The first feature should be the most important */
    val importances = DTMeta.getFeatureImportance().get
    assert(importances(1) == importances.max)
    /* They should all be non-zero */
    assert(importances.min > 0.0)

    val tmpFile: File = File.createTempFile("tmp", ".csv")
    val oos = new ObjectOutputStream(new FileOutputStream(tmpFile))
    oos.writeObject(DT)
  }

  /**
    * Test a really short tree to make sure the linear model feature importance gets carried through
    */
  @Test
  def testStumpWithLinearLeaf(): Unit = {
    val trainingData = TestUtils.binTrainingData(
      TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman, seed = 3L),
      inputBins = Seq((11, 8))
    ).asInstanceOf[Seq[(Vector[Any], Double)]]

    val linearLearner = LinearRegressionLearner(regParam = Some(1.0))
    val DTLearner = RegressionTreeLearner(leafLearner = Some(linearLearner), maxDepth = 0)
    val DTMeta = DTLearner.train(trainingData)
    val DT = DTMeta.getModel()

    val linearImportance = linearLearner.train(trainingData).getFeatureImportance().get

    val importances = DTMeta.getFeatureImportance().get

    /* The first feature should be the most important */
    assert(importances(1) == importances.max)
    /* They should all be non-zero */
    assert(importances.last == 0.0)

    assert(linearImportance.zip(importances).map { case (x, y) => x - y }.forall(d => Math.abs(d) < 1.0e-9),
      s"Expected linear and maxDepth=0 importances to align"
    )

    val result = DT.transform(trainingData.map(_._1))
    assert(result.getDepth().forall(_ == 0), s"Expected all the predictions to be depth 0")
  }

  /**
    * Test with random weights to ensure all feature importances of stump tree with linear leaves are non-negative
    */
  @Test
  def testWeights(): Unit = {
    val trainingData = TestUtils.generateTrainingData(32, 12, noise = 100.0, function = Friedman.friedmanSilverman, seed = 3L)

    val linearLearner = LinearRegressionLearner(regParam = Some(1.0))
    val DTLearner = RegressionTreeLearner(leafLearner = Some(linearLearner), maxDepth = 1)
    val DTMeta = DTLearner.train(trainingData, weights = Some(Seq.fill(trainingData.size) {
      Random.nextInt(8)
    }))
    val DT = DTMeta.getModel()

    /* The first feature should be the most important */
    val importances = DTMeta.getFeatureImportance().get
    assert(importances.forall(_ >= 0.0), "Found negative feature importance")
  }

}

/** Companion driver */
object RegressionTreeTest {
  /**
    * Test driver
    *
    * @param argv args
    */
  def main(argv: Array[String]): Unit = {
    new RegressionTreeTest().testStumpWithLinearLeaf()
  }
}
