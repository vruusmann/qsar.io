/*
 * Copyright (c) 2014 Villu Ruusmann
 *
 * This file is part of QSAR.io
 *
 * QSAR.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QSAR.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with QSAR.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.qsar.toolkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.qsar.descriptor.CDKDescriptorFunction;
import org.dmg.pmml.Apply;
import org.dmg.pmml.Constant;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.Expression;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldRef;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Model;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.TransformationDictionary;
import org.dmg.pmml.Visitor;
import org.dmg.pmml.VisitorAction;
import org.jpmml.model.MetroJAXBUtil;
import org.jpmml.model.PMMLUtil;
import org.jpmml.model.visitors.AbstractVisitor;
import org.jpmml.model.visitors.DataDictionaryCleaner;
import org.jpmml.model.visitors.TransformationDictionaryCleaner;

public class ModelEnhancer {

	@Parameter (
		names = {"--input"},
		description = "Input PMML file",
		required = true
	)
	private File input = null;

	@Parameter (
		names = {"--output"},
		description = "Output PMML file",
		required = true
	)
	private File output = null;


	static
	public void main(String... args) throws Exception {
		ModelEnhancer enhancer = new ModelEnhancer();

		JCommander commander = new JCommander(enhancer);
		commander.setProgramName(ModelEnhancer.class.getName());

		try {
			commander.parse(args);
		} catch(ParameterException pe){
			commander.usage();

			pe.printStackTrace(System.out);

			System.exit(-1);
		}

		enhancer.run();
	}

	private void run() throws Exception {
		PMML pmml;

		try(InputStream is = new FileInputStream(this.input)){
			pmml = PMMLUtil.unmarshal(is);
		}

		pmml = enhance(pmml);

		try(OutputStream os = new FileOutputStream(this.output)){
			MetroJAXBUtil.marshalPMML(pmml, os);
		}
	}

	static
	private PMML enhance(PMML pmml){
		List<Model> models = pmml.getModels();
		if(models.size() != 1){
			throw new IllegalArgumentException();
		}

		Model model = models.get(0);

		final
		FieldName structure = FieldName.create("SMILES");

		DataDictionary dataDictionary = pmml.getDataDictionary();

		TransformationDictionary transformationDictionary = pmml.getTransformationDictionary();
		if(transformationDictionary == null){
			transformationDictionary = new TransformationDictionary();

			pmml.setTransformationDictionary(transformationDictionary);
		}

		MiningSchema miningSchema = model.getMiningSchema();

		final
		Set<FieldName> activeFields = getActiveFields(miningSchema);

		for(Iterator<DataField> it = (dataDictionary.getDataFields()).iterator(); it.hasNext(); ){
			DataField dataField = it.next();

			if(activeFields.contains(dataField.getName())){
				it.remove();

				DerivedField derivedField = new DerivedField(dataField.getOpType(), dataField.getDataType())
					.setName(dataField.getName())
					.setExpression(createExpression((dataField.getName()).getValue(), structure));

				transformationDictionary.addDerivedFields(derivedField);
			}
		}

		dataDictionary.addDataFields(new DataField(structure, OpType.CATEGORICAL, DataType.STRING));

		Visitor visitor = new AbstractVisitor(){

			@Override
			public VisitorAction visit(MiningSchema miningSchema){
				cleanMiningFields(miningSchema, activeFields);

				// XXX
				miningSchema.addMiningFields(new MiningField(structure));

				return super.visit(miningSchema);
			}
		};
		visitor.applyTo(pmml);

		List<Visitor> cleaners = Arrays.<Visitor>asList(new TransformationDictionaryCleaner(), new DataDictionaryCleaner());
		for(Visitor cleaner : cleaners){
			cleaner.applyTo(pmml);
		}

		return pmml;
	}

	static
	private Set<FieldName> getActiveFields(MiningSchema miningSchema){
		Set<FieldName> result = new LinkedHashSet<>();

		List<MiningField> miningFields = miningSchema.getMiningFields();
		for(MiningField miningField : miningFields){
			MiningField.UsageType usageType = miningField.getUsageType();

			switch(usageType){
				case ACTIVE:
					result.add(miningField.getName());
					break;
				case GROUP:
				case ORDER:
					throw new IllegalArgumentException();
				default:
					break;
			}
		}

		return result;
	}

	static
	private void cleanMiningFields(MiningSchema miningSchema, Set<FieldName> activeFields){
		List<MiningField> miningFields = miningSchema.getMiningFields();

		for(Iterator<MiningField> it = miningFields.iterator(); it.hasNext(); ){
			MiningField miningField = it.next();

			if(activeFields.contains(miningField.getName())){
				it.remove();
			}
		}
	}

	static
	private Expression createExpression(String function, FieldName structure){
		Apply result = new Apply(CDKDescriptorFunction.class.getName())
			.addExpressions(new Constant(function), new FieldRef(structure));

		return result;
	}
}