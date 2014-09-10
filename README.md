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

All ONSMP033 CDK descriptor values will be re-calculated in order to ensure that they are "compatible" with QSAR.io CDK descriptor values.

CDK descriptors are calculated using [CDK Descriptor UI] (https://github.com/rajarshi/cdkdescui) software. A suitable CDKDescUI input file can be created by selecting the `SMILES` and `Ave °C` columns, and saving the resulting dataset in Tab-separated values (TSV) file format. The first ten lines of this TSV file are shown below:

```
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

The output method of CDKDescUI should be set to CSV (`Options` -> `Output Method` -> `Comma delimited`). This example assumes that all available CDK descriptors are selected for calculation.

The first column of the CDKDescUI output CSV file is fixed as `Title`. This must be changed to `mp`, which is the name of the current QSAR endpoint. The remaining columns are CDK descriptors.

The RF model is trained and exported in PMML data format using the following R script:

```R
library("pmml")
library("randomForest")

data = read.csv("ONSMP033.csv", header = TRUE, colClasses = rep("numeric", 281))

# Skip columns that contain NA values
col.na = apply(data, 2, function(x){ return (any(is.na(x))) })
data = data[, !col.na]

# Skip columns that are constant
col.const = apply(data, 2, function(x){ return ((var(x)) == 0) })
data = data[, !col.const]

# Skip columns that are poorly correlated (R2 < 0.1) with the property column
col.cor = apply(data, 2, function(x){ return (cor(data$mp, x)^2 < 0.1) })
data = data[, !col.cor]

data.rf = randomForest(mp ~ ., data = data, importance = TRUE, nodesize = 20)

varImpPlot(data.rf)

plot(data$mp, predict(data.rf))

saveXML(pmml(data.rf), file = "ONSMP033.pmml")
```

The `MiningSchema` element of the RF model declares 56 `MiningField` elements - one for the dependent field and 55 for independent fields. It is not particulary easy to use this model for prediction, because the user must supply CDK descriptor values manually.

User experience can be improved by refactoring the contents of selected elements so that the calculation of CDK descriptor values is handled by the PMML scoring engine. The module `qsar-descriptor` contains the CDK descriptor UDF class `io.qsar.descriptor.CDKDescriptorFunction`. This approach can be used to integrate other Java libraries.

The module `qsar-toolkit` contains a command-line application class `io.qsar.toolkit.ModelEnhancer` that automates the refactoring of PMML documents:
```
java -cp qsar-toolkit-executable-1.0-SNAPSHOT.jar io.qsar.toolkit.ModelEnhancer --input ONSMP033.pmml --output ONSMP033-cdk.pmml 
```

In brief, the refactoring performs the following changes:
* Adds a new input field `structure` to `DataDictionary` and `MiningSchema` elements.
* Removes existing CDK descriptor input fields from `DataDictionary` and `MiningSchema` elements, and redefines them as `DerivedField` elements under the `LocalTransformations` element.

Before refactoring:
```xml
<PMML version="4.2">
  <DataDictionary>
    <DataField name="mp" optype="continuous" dataType="double"/>
    <DataField name="naAromAtom" optype="continuous" dataType="double"/>
    <!-- 50 more CDK descriptor input fields -->
  </DataDictionary>
  <MiningModel functionName="regression">
    <MiningSchema>
      <MiningField name="mp" usageType="predicted"/>
      <MiningField name="naAromAtom" usageType="active"/>
      <!-- 50 more CDK descriptor input fields -->
    </MiningSchema>
  </MiningModel>
</PMML>
```

After refactoring:
```xml
<PMML version="4.2">
  <DataDictionary>
    <DataField name="mp" optype="continuous" dataType="double"/>
    <DataField name="structure" optype="categorical" dataType="string"/>
  </DataDictionary>
  <MiningModel functionName="regression">
    <MiningSchema>
      <MiningField name="mp" usageType="predicted"/>
      <MiningField name="structure"/>
    </MiningSchema>
    <LocalTransformations>
      <DerivedField name="naAromAtom" optype="continuous" dataType="double">
        <Apply function="io.qsar.descriptor.CDKDescriptorFunction">
          <Constant>naAromAtom</Constant>
          <FieldRef field="structure"/>
        </Apply>
      </DerivedField>
      <!-- 50 more CDK descriptor definitions -->
    </LocalTransformations>
  </MiningModel>
</PMML>
```

PMML files that are enhanced with CDK descriptors require that the runtime JAR file `qsar-descriptor-runtime-1.0-SNAPSHOT.jar` is added to the classpath of the PMML application.

For example, the [Openscoring REST web service] (https://github.com/jpmml/openscoring) should be started using the following command:
```
java -cp "server-executable-1.2-SNAPSHOT.jar:qsar-descriptor-runtime-1.0-SNAPSHOT.jar" org.openscoring.server.Main
```

Deploying the ONSMP033-cdk.pmml model:
```
curl -X PUT --data-binary @ONSMP033-cdk.pmml -H "Content-type: text/xml" http://localhost:8080/openscoring/model/ONSMP033
```

Performing an evaluation:
```
curl -X POST --data-binary @request.json -H "Content-type: application/json" http://localhost:8080/openscoring/model/ONSMP033
```

The contents of the Openscoring request object file `request.json` is shown below:

```json
{
	"id" : "example-001",
	"arguments" : {
		"structure" : "C(C)CO"
	}
} 
```