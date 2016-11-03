/*
 * Copyright (c) 2016 Villu Ruusmann
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.qsar.descriptor.CDKDescriptorFunction;
import org.dmg.pmml.DataType;
import org.dmg.pmml.OpType;
import org.jpmml.evaluator.CsvUtil;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.FieldValueUtil;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.IMolecularDescriptor;

public class DescriptorCalculator {

	@Parameter (
		names = {"--input"},
		description = "Input TSV file",
		required = true
	)
	private File input = null;

	@Parameter (
		names = {"--output"},
		description = "Output TSV file",
		required = true
	)
	private File output = null;


	static
	public void main(String... args) throws Exception {
		DescriptorCalculator calculator = new DescriptorCalculator();

		JCommander commander = new JCommander(calculator);
		commander.setProgramName(DescriptorCalculator.class.getName());

		try {
			commander.parse(args);
		} catch(ParameterException pe){
			commander.usage();

			pe.printStackTrace(System.out);

			System.exit(-1);
		}

		calculator.run();
	}

	private void run() throws Exception {
		CsvUtil.Table table;

		try(InputStream is = new FileInputStream(this.input)){
			table = CsvUtil.readTable(is, "\t");
		}

		table = calculate(table);

		try(OutputStream os = new FileOutputStream(this.output)){
			CsvUtil.writeTable(table, os);
		}
	}

	static
	private CsvUtil.Table calculate(CsvUtil.Table table) throws Exception {
		List<String> ids = new ArrayList<>(CDKDescriptorFunction.getMolecularDescriptorIds());

		List<FieldValue> descriptors = new ArrayList<>();

		CDKDescriptorFunction function = new CDKDescriptorFunction();

		{
			List<String> headerRow = table.get(0);

			IAtomContainer methane = CDKDescriptorFunction.parseAtomContainer("C");

			ids:
			for(String id : ids){
				IMolecularDescriptor molecularDescriptor = CDKDescriptorFunction.getMolecularDescriptor(id);

				DescriptorValue descriptorValue = molecularDescriptor.calculate(methane);

				Exception exception = descriptorValue.getException();
				if(exception != null){
					System.err.println("Skipping descriptor \"" + id + "\": " + exception.getMessage());

					continue ids;
				}

				FieldValue descriptor = FieldValueUtil.create(DataType.STRING, OpType.CATEGORICAL, id);

				headerRow.add(id);

				descriptors.add(descriptor);
			}
		}

		for(int i = 1; i < table.size(); i++){
			List<String> bodyRow = table.get(i);

			FieldValue structure = FieldValueUtil.create(DataType.STRING, OpType.CATEGORICAL, bodyRow.get(0));

			System.out.println("Calculating structure " + String.valueOf(i) + ": " + structure.getValue());

			for(FieldValue descriptor : descriptors){
				FieldValue result;

				try {
					result = function.evaluate(Arrays.asList(descriptor, structure));
				} catch(Exception e){
					result = null;
				}

				Object value = FieldValueUtil.getValue(result);

				if(value instanceof Double){
					Double doubleValue = (Double)value;

					if(doubleValue.isNaN()){
						value = null;
					}
				}

				bodyRow.add(value != null ? value.toString() : "N/A");
			}
		}

		return table;
	}
}