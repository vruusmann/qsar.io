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
import org.dmg.pmml.LocalTransformations;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Model;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.jpmml.model.PMMLUtil;

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
			PMMLUtil.marshal(pmml, os);
		}
	}

	static
	private PMML enhance(PMML pmml){
		List<Model> models = pmml.getModels();
		if(models.size() != 1){
			throw new IllegalArgumentException();
		}

		Model model = models.get(0);

		FieldName structure = FieldName.create("structure");

		DataDictionary dataDictionary = pmml.getDataDictionary();

		LocalTransformations localTransformations = model.getLocalTransformations();
		if(localTransformations == null){
			localTransformations = new LocalTransformations();

			model.setLocalTransformations(localTransformations);
		}

		MiningSchema miningSchema = model.getMiningSchema();

		Set<FieldName> activeFields = getActiveFields(miningSchema);

		for(Iterator<DataField> it = (dataDictionary.getDataFields()).iterator(); it.hasNext(); ){
			DataField dataField = it.next();

			if(activeFields.contains(dataField.getName())){
				it.remove();

				DerivedField derivedField = new DerivedField(dataField.getOpType(), dataField.getDataType())
					.setName(dataField.getName())
					.setExpression(createExpression((dataField.getName()).getValue(), structure));

				localTransformations.addDerivedFields(derivedField);
			}
		}

		dataDictionary.addDataFields(new DataField(structure, OpType.CATEGORICAL, DataType.STRING));

		for(Iterator<MiningField> it = (miningSchema.getMiningFields()).iterator(); it.hasNext(); ){
			MiningField miningField = it.next();

			if(activeFields.contains(miningField.getName())){
				it.remove();
			}
		}

		miningSchema.addMiningFields(new MiningField(structure));

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
	private Expression createExpression(String function, FieldName structure){
		Apply result = new Apply(CDKDescriptorFunction.class.getName())
			.addExpressions(new Constant(function), new FieldRef(structure));

		return result;
	}
}