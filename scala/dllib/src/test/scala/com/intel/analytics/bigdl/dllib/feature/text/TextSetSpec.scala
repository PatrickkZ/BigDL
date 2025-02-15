/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
package com.intel.analytics.bigdl.dllib.feature.text

import com.intel.analytics.bigdl.dllib.optim.{Adagrad, SGD}
import com.intel.analytics.bigdl.dllib.tensor.TensorNumericMath.TensorNumeric.NumericFloat
import com.intel.analytics.bigdl.dllib.utils.Shape
import com.intel.analytics.bigdl.dllib.NNContext
import com.intel.analytics.bigdl.dllib.feature.common.Relation
import com.intel.analytics.zoo.models.textclassification.TextClassifier
import com.intel.analytics.zoo.models.textmatching.KNRM
import com.intel.analytics.zoo.pipeline.api.keras.ZooSpecHelper
import com.intel.analytics.zoo.pipeline.api.keras.layers.TimeDistributed
import com.intel.analytics.zoo.pipeline.api.keras.metrics.Accuracy
import com.intel.analytics.zoo.pipeline.api.keras.models.Sequential
import com.intel.analytics.bigdl.dllib.keras.objectives.{RankHinge, SparseCategoricalCrossEntropy}
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.immutable.HashSet

class TextSetSpec extends ZooSpecHelper {
  val text1 = "Hello my friend, please annotate my text"
  val text2 = "hello world, this is some sentence for my test"
  val path: String = getClass.getClassLoader.getResource("news20").getPath
  var sc : SparkContext = _
  val gloveDir: String = getClass.getClassLoader.getResource("glove.6B").getPath
  val embeddingFile: String = gloveDir + "/glove.6B.50d.txt"
  val qaDir: String = getClass.getClassLoader.getResource("qa").getPath
  val corpus: String = qaDir + "/question_corpus.csv"
  val q1 = "what is your project?"
  val q2 = "how old are you?"

  override def doBefore(): Unit = {
    val conf = new SparkConf().setAppName("Test TextSet").setMaster("local[1]")
    sc = NNContext.initNNContext(conf)
  }

  override def doAfter(): Unit = {
    if (sc != null) {
      sc.stop()
    }
  }

  private def genFeatures(): Array[TextFeature] = {
    val feature1 = TextFeature(text1, label = 0)
    val feature2 = TextFeature(text2, label = 1)
    Array(feature1, feature2)
  }

  "DistributedTextSet Transformation" should "work properly" in {
    val distributed = TextSet.rdd(sc.parallelize(genFeatures()))
    TestUtils.conditionFailTest(distributed.isDistributed)
    val normalized = distributed -> Tokenizer() -> Normalizer()
    val transformed = normalized.word2idx().shapeSequence(5).generateSample()
    TestUtils.conditionFailTest(transformed.isDistributed)

    val wordIndex = transformed.getWordIndex
    TestUtils.conditionFailTest(wordIndex.toArray.length == 13)
    TestUtils.conditionFailTest(
    wordIndex.keySet == HashSet("hello", "friend", "please", "annotate", "my", "text",
      "world", "some", "sentence", "for", "test", "this", "is"))

    val features = transformed.toDistributed().rdd.collect()
    TestUtils.conditionFailTest(features.length == 2)
    TestUtils.conditionFailTest(
    features(0).keys() == HashSet("label", "text", "tokens", "indexedTokens", "sample"))
    TestUtils.conditionFailTest(
    features(0)[Array[Float]]("indexedTokens").length == 5)

    val tmpFile = createTmpFile()
    transformed.saveWordIndex(tmpFile.getAbsolutePath)

    val distributed2 = TextSet.rdd(sc.parallelize(genFeatures().take(1)))
      .loadWordIndex(tmpFile.getAbsolutePath)
    TestUtils.conditionFailTest(distributed2.getWordIndex == wordIndex)
    val transformed2 = distributed2.tokenize().normalize().word2idx()
      .shapeSequence(4, TruncMode.post)
    val indices = transformed2.toDistributed().rdd.first().getIndices.map(_.toInt)
    TestUtils.conditionFailTest(
    indices.sameElements(Array(wordIndex("hello"), wordIndex("my"),
      wordIndex("friend"), wordIndex("please"))))
  }

  "LocalTextSet Transformation" should "work properly" in {
    val local = TextSet.array(genFeatures())
    TestUtils.conditionFailTest(local.isLocal)
    val transformed = local.tokenize().normalize().word2idx(removeTopN = 1)
      .shapeSequence(len = 10).generateSample()
    TestUtils.conditionFailTest(transformed.isLocal)

    val wordIndex = transformed.getWordIndex
    TestUtils.conditionFailTest(wordIndex.toArray.length == 12)
    TestUtils.conditionFailTest(wordIndex.keySet.contains("hello"))
    TestUtils.conditionFailTest(!wordIndex.keySet.contains("Hello"))

    val features = transformed.toLocal().array
    TestUtils.conditionFailTest(features.length == 2)
    TestUtils.conditionFailTest(
    features(0).keys() == HashSet("label", "text", "tokens", "indexedTokens", "sample"))
    TestUtils.conditionFailTest(features(0).getIndices.length == 10)

    val tmpFile = createTmpFile()
    transformed.saveWordIndex(tmpFile.getAbsolutePath)

    val local2 = TextSet.array(genFeatures().take(1))
      .loadWordIndex(tmpFile.getAbsolutePath)
    TestUtils.conditionFailTest(local2.getWordIndex == wordIndex)
    val transformed2 = local2.tokenize().normalize().word2idx().shapeSequence(4)
    val indices = transformed2.toLocal().array(0).getIndices.map(_.toInt)
    TestUtils.conditionFailTest(
    indices.sameElements(Array(wordIndex("friend"), wordIndex("please"),
      wordIndex("annotate"), wordIndex("text"))))
  }

  "Save a TextSet with no wordIndex" should "raise an error" in {
    intercept[Exception] {
      val tmpFile = createTmpFile()
      val distributed = TextSet.rdd(sc.parallelize(genFeatures()))
      distributed.saveWordIndex(tmpFile.getAbsolutePath)
    }
  }

  "TextSet read with sc, fit, predict and evaluate" should "work properly" in {
    val textSet = TextSet.read(path, sc)
    TestUtils.conditionFailTest(textSet.isDistributed)
    TestUtils.conditionFailTest(textSet.toDistributed().rdd.count() == 5)
    TestUtils.conditionFailTest(
    textSet.toDistributed().rdd.collect().head.keys() == HashSet("label", "text", "uri"))
    val transformed = textSet.tokenize().normalize().word2idx()
      .shapeSequence(len = 30).generateSample()
    val model = TextClassifier(3, embeddingFile, transformed.getWordIndex, 30)
    model.compile(new SGD[Float](), SparseCategoricalCrossEntropy[Float](), List(new Accuracy()))
    model.fit(transformed, batchSize = 4, nbEpoch = 2, validationData = transformed)
    TestUtils.conditionFailTest(! transformed.toDistributed().rdd.first().contains("predict"))

    val predictSet = model.predict(transformed, batchPerThread = 2).toDistributed()
    val textFeatures = predictSet.rdd.collect()
    textFeatures.foreach(feature => {
      TestUtils.conditionFailTest(feature.contains("predict"))
      val input = feature.getSample.feature.reshape(Array(1, 30))
      val output = model.setEvaluateStatus().forward(input).toTensor[Float].split(1)(0)
      feature.getPredict[Float] should be (output)
    })
    val accuracy = model.evaluate(transformed, batchSize = 4)

    // Test for loaded model predict on TextSet
    val saveFile = createTmpFile()
    model.saveModel(saveFile.getAbsolutePath, overWrite = true)
    val loadedModel = TextClassifier.loadModel[Float](saveFile.getAbsolutePath)
    val predictResults = loadedModel.predict(transformed, batchPerThread = 2)
      .toDistributed().rdd.collect()
  }

  "TextSet read without sc, fit, predict and evaluate" should "work properly" in {
    val textSet = TextSet.read(path)
    TestUtils.conditionFailTest(textSet.isLocal)
    TestUtils.conditionFailTest(textSet.toLocal().array.length == 5)
    TestUtils.conditionFailTest(
    textSet.toLocal().array.head.keys() == HashSet("label", "text", "uri"))
    val tokenized = textSet -> Tokenizer() -> Normalizer()
    val wordIndex = tokenized.generateWordIndexMap()
    val transformed = tokenized -> WordIndexer(wordIndex) -> SequenceShaper(len = 30) ->
      TextFeatureToSample()
    TestUtils.conditionFailTest(transformed.getWordIndex == wordIndex)
    val model = TextClassifier(10, embeddingFile, wordIndex, 30)
    model.compile(new Adagrad[Float](), SparseCategoricalCrossEntropy[Float](),
      List(new Accuracy()))
    model.fit(transformed, batchSize = 4, nbEpoch = 2, validationData = transformed)
    TestUtils.conditionFailTest(! transformed.toLocal().array.head.contains("predict"))

    val predictSet = model.predict(transformed, batchPerThread = 2).toLocal()
    val textFeatures = predictSet.array
    textFeatures.foreach(feature => {
      TestUtils.conditionFailTest(feature.contains("predict"))
      val input = feature.getSample.feature.reshape(Array(1, 30))
      val output = model.setEvaluateStatus().forward(input).toTensor[Float].split(1)(0)
      feature.getPredict[Float] should be(output)
    })
    val accuracy = model.evaluate(transformed, batchSize = 4)

    val saveFile = createTmpFile()
    model.saveModel(saveFile.getAbsolutePath, overWrite = true)
    val loadedModel = TextClassifier.loadModel[Float](saveFile.getAbsolutePath)
    val predictResults = loadedModel.predict(transformed, batchPerThread = 2).toLocal().array
  }

  "TextSet read corpus with sc" should "work properly" in {
    val textSet = TextSet.readCSV(corpus, sc, 2)
    TestUtils.conditionFailTest(textSet.isDistributed)
    val features = textSet.toDistributed().rdd.collect()
    TestUtils.conditionFailTest(features.length == 2)
    val texts = features.map(_.getText).toSet
    TestUtils.conditionFailTest(texts == Set(q1, q2))
  }

  "TextSet read corpus without sc" should "work properly" in {
    val textSet = TextSet.readCSV(corpus)
    TestUtils.conditionFailTest(textSet.isLocal)
    val features = textSet.toLocal().array
    TestUtils.conditionFailTest(features.length == 2)
    val texts = features.map(_.getText).toSet
    TestUtils.conditionFailTest(texts == Set(q1, q2))
  }

  "TextSet read parquet" should "work properly" in {
    val textSet = TextSet.readParquet(qaDir + "/question_corpus.parquet",
      SQLContext.getOrCreate(sc))
    TestUtils.conditionFailTest(textSet.isDistributed)
    val features = textSet.toDistributed().rdd.collect()
    TestUtils.conditionFailTest(features.length == 2)
    val texts = features.map(_.getText).toSet
    TestUtils.conditionFailTest(texts == Set(q1, q2))
  }

  "TextSet word2idx with minFreq and existingMap" should "work properly" in {
    val text = "hello my my my world hello how are you you"
    val feature = TextFeature(text)
    val distributed = TextSet.rdd(sc.parallelize(Seq(feature)))
    val distributedTransformed = distributed.tokenize().word2idx(minFreq = 2,
      existingMap = Map("hello" -> 1, "test" -> 2))
    val wordIndex = distributedTransformed.getWordIndex
    TestUtils.conditionFailTest(wordIndex("hello") == 1)
    TestUtils.conditionFailTest(wordIndex("test") == 2)
    TestUtils.conditionFailTest(wordIndex.keySet == Set("hello", "test", "my", "you"))
    TestUtils.conditionFailTest(wordIndex.values.toArray.sorted.sameElements(Array(1, 2, 3, 4)))
    val my = wordIndex("my").toFloat
    val you = wordIndex("you").toFloat
    val indices = distributedTransformed.toDistributed().rdd.collect()(0).getIndices
    TestUtils.conditionFailTest(indices.sameElements(Array(1.0f, my, my, my, 1.0f, you, you)))

    val local = TextSet.array(Array(feature))
    val localTransformed = local.tokenize().word2idx(removeTopN = 1, minFreq = 2,
      existingMap = Map("world" -> 2))
    val wordIndex2 = localTransformed.getWordIndex
    TestUtils.conditionFailTest(wordIndex2("world") == 2)
    TestUtils.conditionFailTest(wordIndex2.keySet == Set("hello", "world", "you"))
    TestUtils.conditionFailTest(wordIndex2.values.toArray.sorted.sameElements(Array(2, 3, 4)))
    val hello = wordIndex2("hello").toFloat
    val you2 = wordIndex2("you").toFloat
    val indices2 = localTransformed.toLocal().array(0).getIndices
    TestUtils.conditionFailTest(indices2.sameElements(Array(hello, 2.0f, hello, you2, you2)))
  }

  "TextSet from relation pairs and lists with training and validation" should "work properly" in {
    val relations =
     Array(Relation("Q1", "A1", 1), Relation("Q2", "A1", 0), Relation("Q2", "A2", 1),
      Relation("Q2", "A3", 0))
    val relationsRDD = sc.parallelize(relations)
    val qIndices = Array(1.0f, 2.0f, 3.0f)
    val q1 = TextFeature(null, uri = "Q1")
    q1(TextFeature.indexedTokens) = qIndices
    val q2 = TextFeature(null, uri = "Q2")
    q2(TextFeature.indexedTokens) = qIndices
    val qSet = TextSet.rdd(sc.parallelize(Seq(q1, q2)))
    val aIndices = Array(2.0f, 2.0f, 3.0f, 5.0f, 4.0f, 0.0f)
    val a1 = TextFeature(null, uri = "A1")
    a1(TextFeature.indexedTokens) = aIndices
    val a2 = TextFeature(null, uri = "A2")
    a2(TextFeature.indexedTokens) = aIndices
    val a3 = TextFeature(null, uri = "A3")
    a3(TextFeature.indexedTokens) = aIndices
    val aSet = TextSet.rdd(sc.parallelize(Seq(a1, a2, a3)))
    val pairSet = TextSet.fromRelationPairs(relationsRDD, qSet, aSet)
    TestUtils.conditionFailTest(pairSet.isDistributed)
    val pairFeatures = pairSet.toDistributed().rdd.collect()
    TestUtils.conditionFailTest(pairFeatures.length == 2)
    TestUtils.conditionFailTest(pairFeatures.map(_.getURI).toSet == Set("Q2A2A1", "Q2A2A3"))
    pairFeatures.foreach(feature => {
      val sample = feature.getSample
      TestUtils.conditionFailTest(sample.feature().size().sameElements(Array(2, 9)))
      TestUtils.conditionFailTest(sample.feature().reshape(Array(18)).toArray().sameElements(
        qIndices ++ aIndices ++ qIndices ++ aIndices))
      TestUtils.conditionFailTest(sample.label().size().sameElements(Array(2, 1)))
      TestUtils.conditionFailTest(
      sample.label().reshape(Array(2)).toArray().sameElements(Array(1.0f, 0.0f)))
    })

    val listSet = TextSet.fromRelationLists(relationsRDD, qSet, aSet)
    TestUtils.conditionFailTest(listSet.isDistributed)
    val listFeatures = listSet.toDistributed().rdd.collect().sortBy(_.getURI.length)
    TestUtils.conditionFailTest(listFeatures.length == 2)
    val listFeature1 = listFeatures(0)
    TestUtils.conditionFailTest(listFeature1.getURI == "Q1A1")
    val sample1 = listFeature1.getSample
    TestUtils.conditionFailTest(sample1.feature().size().sameElements(Array(1, 9)))
    TestUtils.conditionFailTest(
    sample1.feature().reshape(Array(9)).toArray().sameElements(qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample1.label().size().sameElements(Array(1, 1)))
    TestUtils.conditionFailTest(
    sample1.label().reshape(Array(1)).toArray().sameElements(Array(1.0f)))
    val listFeature2 = listFeatures(1)
    TestUtils.conditionFailTest(listFeature2.getURI.startsWith("Q2"))
    TestUtils.conditionFailTest(
    listFeature2.getURI.contains("A1") && listFeature2.getURI.contains("A2") &&
      listFeature2.getURI.contains("A3"))
    val sample2 = listFeature2.getSample
    TestUtils.conditionFailTest(sample2.feature().size().sameElements(Array(3, 9)))
    TestUtils.conditionFailTest(
    sample2.feature().reshape(Array(27)).toArray().sameElements(qIndices ++ aIndices
      ++ qIndices ++ aIndices ++ qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample2.label().size().sameElements(Array(3, 1)))
    TestUtils.conditionFailTest(sample2.label().reshape(Array(3)).toArray().sorted
      .sameElements(Array(0.0f, 0.0f, 1.0f)))

    val gloveDir = getClass.getClassLoader.getResource("glove.6B").getPath
    val embeddingFile = gloveDir + "/glove.6B.50d.txt"
    val knrm = KNRM[Float](3, 6, embeddingFile)
    val model = Sequential().add(TimeDistributed(knrm, inputShape = Shape(2, 9)))
    model.compile(optimizer = new SGD[Float](), loss = RankHinge[Float]())
    model.fit(pairSet, batchSize = 2, nbEpoch = 2)
    knrm.evaluateNDCG(listSet, 3)
    knrm.evaluateNDCG(listSet, 5)
    knrm.evaluateMAP(listSet)
  }

  "Array TextSet from relation pairs and lists with training and validation" should
    "work properly" in {
    val relations = Array(Relation("Q1", "A1", 1), Relation("Q1", "A2", 0),
      Relation("Q2", "A1", 0), Relation("Q2", "A2", 1),
      Relation("Q2", "A3", 0))
    val qIndices = Array(1.0f, 2.0f, 3.0f)
    val q1 = TextFeature(null, uri = "Q1")
    q1(TextFeature.indexedTokens) = qIndices
    val q2 = TextFeature(null, uri = "Q2")
    q2(TextFeature.indexedTokens) = qIndices
    val qSet = TextSet.array(Array(q1, q2))
    val aIndices = Array(2.0f, 2.0f, 3.0f, 5.0f, 4.0f, 0.0f)
    val a1 = TextFeature(null, uri = "A1")
    a1(TextFeature.indexedTokens) = aIndices
    val a2 = TextFeature(null, uri = "A2")
    a2(TextFeature.indexedTokens) = aIndices
    val a3 = TextFeature(null, uri = "A3")
    a3(TextFeature.indexedTokens) = aIndices
    val aSet = TextSet.array(Array(a1, a2, a3))
    val pairSet = TextSet.fromRelationPairs(relations, qSet, aSet)
    TestUtils.conditionFailTest(pairSet.isLocal)
    val pairFeatures = pairSet.toLocal().array
    TestUtils.conditionFailTest(pairFeatures.length == 3)
    TestUtils.conditionFailTest(
    pairFeatures.map(_.getURI).toSet == Set("Q1A1A2", "Q2A2A1", "Q2A2A3"))

    for(feature <- pairFeatures) {
      val sample = feature.getSample
      TestUtils.conditionFailTest(sample.feature().size().sameElements(Array(2, 9)))
      TestUtils.conditionFailTest(
      sample.feature().reshape(Array(18)).toArray().sameElements(
        qIndices ++ aIndices ++ qIndices ++ aIndices))
      TestUtils.conditionFailTest(sample.label().size().sameElements(Array(2, 1)))
      TestUtils.conditionFailTest(
      sample.label().reshape(Array(2)).toArray().sameElements(Array(1.0f, 0.0f)))
    }

    val listSet = TextSet.fromRelationLists(relations, qSet, aSet)
    TestUtils.conditionFailTest(listSet.isLocal)
    val listFeatures = listSet.toLocal().array.sortBy(_.getURI.length)
    TestUtils.conditionFailTest(listFeatures.length == 2)
    val listFeature1 = listFeatures(0)
    TestUtils.conditionFailTest(listFeature1.getURI.startsWith("Q1"))
    TestUtils.conditionFailTest(
    listFeature1.getURI.contains("A1") && listFeature1.getURI.contains("A2"))
    val sample1 = listFeature1.getSample
    TestUtils.conditionFailTest(sample1.feature().size().sameElements(Array(2, 9)))
    TestUtils.conditionFailTest(sample1.feature().reshape(Array(18)).toArray()
      .sameElements(qIndices ++ aIndices ++ qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample1.label().size().sameElements(Array(2, 1)))
    TestUtils.conditionFailTest(sample1.label().reshape(Array(2)).toArray().sorted.
      sameElements(Array(0.0f, 1.0f)))
    val listFeature2 = listFeatures(1)
    TestUtils.conditionFailTest(listFeature2.getURI.startsWith("Q2"))
    TestUtils.conditionFailTest(
    listFeature2.getURI.contains("A1") && listFeature2.getURI.contains("A2") &&
      listFeature2.getURI.contains("A3"))
    val sample2 = listFeature2.getSample
    TestUtils.conditionFailTest(sample2.feature().size().sameElements(Array(3, 9)))
    TestUtils.conditionFailTest(
    sample2.feature().reshape(Array(27)).toArray().sameElements(qIndices ++ aIndices
      ++ qIndices ++ aIndices ++ qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample2.label().size().sameElements(Array(3, 1)))
    TestUtils.conditionFailTest(sample2.label().reshape(Array(3)).toArray().sorted
      .sameElements(Array(0.0f, 0.0f, 1.0f)))

    val gloveDir = getClass.getClassLoader.getResource("glove.6B").getPath
    val embeddingFile = gloveDir + "/glove.6B.50d.txt"
    val knrm = KNRM[Float](3, 6, embeddingFile)
    val model = Sequential().add(TimeDistributed(knrm, inputShape = Shape(2, 9)))
    model.compile(optimizer = new SGD[Float](), loss = RankHinge[Float]())
    model.fit(pairSet, batchSize = 2, nbEpoch = 2)
    knrm.evaluateNDCG(listSet, 3)
    knrm.evaluateNDCG(listSet, 5)
    knrm.evaluateMAP(listSet)
  }

  "Array2 TextSet from relation pairs and lists with training and validation" should
    "work properly" in {
    val relations =
     Array(Relation("Q1", "A1", 1), Relation("Q2", "A1", 0), Relation("Q2", "A2", 1),
      Relation("Q2", "A3", 0))
    val qIndices = Array(1.0f, 2.0f, 3.0f)
    val q1 = TextFeature(null, uri = "Q1")
    q1(TextFeature.indexedTokens) = qIndices
    val q2 = TextFeature(null, uri = "Q2")
    q2(TextFeature.indexedTokens) = qIndices
    val qSet = TextSet.array(Array(q1, q2))
    val aIndices = Array(2.0f, 2.0f, 3.0f, 5.0f, 4.0f, 0.0f)
    val a1 = TextFeature(null, uri = "A1")
    a1(TextFeature.indexedTokens) = aIndices
    val a2 = TextFeature(null, uri = "A2")
    a2(TextFeature.indexedTokens) = aIndices
    val a3 = TextFeature(null, uri = "A3")
    a3(TextFeature.indexedTokens) = aIndices
    val aSet = TextSet.array(Array(a1, a2, a3))
    val pairSet = TextSet.fromRelationPairs(relations, qSet, aSet)
    TestUtils.conditionFailTest(pairSet.isLocal)
    val pairFeatures = pairSet.toLocal().array
    TestUtils.conditionFailTest(pairFeatures.length == 2)
    TestUtils.conditionFailTest(pairFeatures.map(_.getURI).toSet == Set("Q2A2A1", "Q2A2A3"))
    pairFeatures.foreach(feature => {
      val sample = feature.getSample
      TestUtils.conditionFailTest(sample.feature().size().sameElements(Array(2, 9)))
      TestUtils.conditionFailTest(sample.feature().reshape(Array(18)).toArray().sameElements(
        qIndices ++ aIndices ++ qIndices ++ aIndices))
      TestUtils.conditionFailTest(sample.label().size().sameElements(Array(2, 1)))
      TestUtils.conditionFailTest(
      sample.label().reshape(Array(2)).toArray().sameElements(Array(1.0f, 0.0f)))
    })

    val listSet = TextSet.fromRelationLists(relations, qSet, aSet)
    TestUtils.conditionFailTest(listSet.isLocal)
    val listFeatures = listSet.toLocal().array.sortBy(_.getURI.length)
    TestUtils.conditionFailTest(listFeatures.length == 2)
    val listFeature1 = listFeatures(0)
    TestUtils.conditionFailTest(listFeature1.getURI == "Q1A1")
    val sample1 = listFeature1.getSample
    TestUtils.conditionFailTest(sample1.feature().size().sameElements(Array(1, 9)))
    TestUtils.conditionFailTest(
    sample1.feature().reshape(Array(9)).toArray().sameElements(qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample1.label().size().sameElements(Array(1, 1)))
    TestUtils.conditionFailTest(
    sample1.label().reshape(Array(1)).toArray().sameElements(Array(1.0f)))
    val listFeature2 = listFeatures(1)
    TestUtils.conditionFailTest(listFeature2.getURI.startsWith("Q2"))
    TestUtils.conditionFailTest(
    listFeature2.getURI.contains("A1") && listFeature2.getURI.contains("A2") &&
      listFeature2.getURI.contains("A3"))
    val sample2 = listFeature2.getSample
    TestUtils.conditionFailTest(sample2.feature().size().sameElements(Array(3, 9)))
    TestUtils.conditionFailTest(
    sample2.feature().reshape(Array(27)).toArray().sameElements(qIndices ++ aIndices
      ++ qIndices ++ aIndices ++ qIndices ++ aIndices))
    TestUtils.conditionFailTest(sample2.label().size().sameElements(Array(3, 1)))
    TestUtils.conditionFailTest(sample2.label().reshape(Array(3)).toArray().sorted
      .sameElements(Array(0.0f, 0.0f, 1.0f)))

    val gloveDir = getClass.getClassLoader.getResource("glove.6B").getPath
    val embeddingFile = gloveDir + "/glove.6B.50d.txt"
    val knrm = KNRM[Float](3, 6, embeddingFile)
    val model = Sequential().add(TimeDistributed(knrm, inputShape = Shape(2, 9)))
    model.compile(optimizer = new SGD[Float](), loss = RankHinge[Float]())
    model.fit(pairSet, batchSize = 2, nbEpoch = 2)
    knrm.evaluateNDCG(listSet, 3)
    knrm.evaluateNDCG(listSet, 5)
    knrm.evaluateMAP(listSet)
  }
}
*/
