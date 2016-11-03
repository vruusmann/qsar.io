QSAR.io 
=======

QSAR user-defined functions for the JPMML-Evaluator library (https://github.com/jpmml/jpmml-evaluator)

# Example workflow #

Step-by-step instructions for developing a RandomForest (RF) model for the [Open Melting Point dataset] (http://onschallenge.wikispaces.com/Open+Melting+Point+Datasets) and deploying it as a REST web service.

Download the curated [ONS Melting Point dataset #33 (ONSMP033)] (http://showme.physics.drexel.edu/onsc/ONSMP033.xlsx). This Microsoft Excel file contains 19410 rows (ie. unique chemical compounds) and over one hundred columns.

Some noteworthy columns:

* `CSID` - ChemSpider ID
* `Low °C` - The lowest known MP value (in degrees of Celsius).
* `High °C` - The highest known MP value (in degrees of Celsius).
* `Ave °C` - The midpoint between the lowest and highest known MP values.
* `SMILES` - The SMILES structure representation.
* `name` - The name.
* `AlogP` through `Zagreb` - Calculated CDK descriptors.

A suitable input file can be created by selecting the `SMILES` and `Ave °C` columns, and saving the resulting dataset in Tab-separated values (TSV) file format. The header line, and the first ten lines of this TSV file are shown below:

```
SMILES	mpC
C	-182.55
N	-77.7
O	0
[3H]O[3H]	3.81
F	-83.55
C#C	-80.75
C#N	-13.4
C=C	-169.28
C=O	-92
CC	-183.06
```

CDK descriptors are calculated using the `io.qsar.toolkit.DescriptorCalculator` command-line application:
```
java -cp target/qsar-distributable-1.0-SNAPSHOT.jar io.qsar.toolkit.DescriptorCalculator --input ONSMP033.tsv --output ONSMP033-cdk.tsv
```

The RF model is trained and exported in PMML data format using the following R script:

```R
library("caret")
library("randomForest")
library("r2pmml")

ONSMP = read.csv("ONSMP033-cdk.tsv", header = TRUE, sep = "\t", na.strings = "N/A")
ONSMP$SMILES = NULL

# Exclude zero- and near zero-Variance predictors
nzvDescr = nearZeroVar(ONSMP)
ONSMP = ONSMP[, -nzvDescr]

# Exclude correlated predictors
highlyCorDescr = findCorrelation(cor(ONSMP, use = "pairwise.complete.obs"), cutoff = .95)
ONSMP = ONSMP[, -highlyCorDescr]

# Exclude rows that contain N/A values
ONSMP = ONSMP[complete.cases(ONSMP), ]

mpC.rf = randomForest(mpC ~ ., data = ONSMP, importance = TRUE, ntree = 50)
print(mpC.rf)

varImpPlot(mpC.rf)

plot(ONSMP$mpC, predict(mpC.rf))

r2pmml(mpC.rf, file = "ONSMP033.pmml")
```

The `MiningSchema` element of the RF model declares over one hundred `MiningField` elements - one for the dependent field and all the others for independent fields. It is not particulary easy to use this model for prediction, because the user must supply CDK descriptor values manually.

User experience can be improved by refactoring the PMML document so that the calculation of CDK descriptor values is handled by the PMML scoring engine. This approach can be used to integrate other Java-based descriptor calculation libraries.

The PMML document is refactored using the `io.qsar.toolkit.ModelEnhancer` command-line application:
```
java -cp target/qsar-distributable-1.0-SNAPSHOT.jar io.qsar.toolkit.ModelEnhancer --input ONSMP033.pmml --output ONSMP033-smiles.pmml 
```

In brief, the refactoring performs the following changes:
* Adds a new input field `SMILES` to `DataDictionary` and `MiningSchema` elements.
* Removes existing CDK descriptor input fields from `DataDictionary` and `MiningSchema` elements, and redefines them as `DerivedField` elements under the `TransformationDictionary` element.

Before refactoring:
```xml
<PMML version="4.3">
  <DataDictionary>
    <DataField name="mpC" optype="continuous" dataType="double"/>
    <DataField name="naAromAtom" optype="continuous" dataType="double"/>
    <!-- 100 more CDK descriptor input fields -->
  </DataDictionary>
  <MiningModel functionName="regression">
    <MiningSchema>
      <MiningField name="mpC" usageType="target"/>
      <MiningField name="naAromAtom"/>
      <!-- 100 more CDK descriptor input fields -->
    </MiningSchema>
  </MiningModel>
</PMML>
```

After refactoring:
```xml
<PMML version="4.3">
  <DataDictionary>
    <DataField name="mpC" optype="continuous" dataType="double"/>
    <DataField name="SMILES" optype="categorical" dataType="string"/>
  </DataDictionary>
  <LocalTransformations>
    <DerivedField name="naAromAtom" optype="continuous" dataType="double">
      <Apply function="io.qsar.descriptor.CDKDescriptorFunction">
        <Constant>naAromAtom</Constant>
        <FieldRef field="SMILES"/>
      </Apply>
    </DerivedField>
    <!-- 100 more CDK descriptor definitions -->
  </LocalTransformations>
  <MiningModel functionName="regression">
    <MiningSchema>
      <MiningField name="mpC" usageType="target"/>
      <MiningField name="SMILES"/>
    </MiningSchema>
  </MiningModel>
</PMML>
```

Melting points for new compounds can be predicted using the `org.jpmml.evaluator.EvaluationExample` command-line application:
```
$ java -cp target/qsar-distributable-1.0-SNAPSHOT.jar org.jpmml.evaluator.EvaluationExample --model ONSMP033-smiles.pmml --input structures.csv --separator "," --output structures-mpC.csv
```
