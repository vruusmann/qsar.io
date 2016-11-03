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
package io.qsar.descriptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import org.jpmml.evaluator.EvaluationException;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.FieldValueUtil;
import org.jpmml.evaluator.FunctionException;
import org.jpmml.evaluator.functions.AbstractFunction;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.aromaticity.CDKHueckelAromaticityDetector;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.graph.ConnectivityChecker;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.qsar.DescriptorEngine;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.IDescriptor;
import org.openscience.cdk.qsar.IMolecularDescriptor;
import org.openscience.cdk.qsar.result.DoubleArrayResult;
import org.openscience.cdk.qsar.result.DoubleResult;
import org.openscience.cdk.qsar.result.IDescriptorResult;
import org.openscience.cdk.qsar.result.IntegerArrayResult;
import org.openscience.cdk.qsar.result.IntegerResult;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.tools.CDKHydrogenAdder;
import org.openscience.cdk.tools.manipulator.AtomContainerManipulator;

public class CDKDescriptorFunction extends AbstractFunction {

	public CDKDescriptorFunction(){
		super(CDKDescriptorFunction.class.getName());
	}

	@Override
	public FieldValue evaluate(List<FieldValue> values){
		checkArguments(values, 2);

		String id = (values.get(0)).asString();

		IMolecularDescriptor molecularDescriptor = getMolecularDescriptor(id);
		if(molecularDescriptor == null){
			throw new FunctionException(getName(), id);
		}

		String structure = (values.get(1)).asString();

		IAtomContainer molecule = getAtomContainer(structure);
		if(molecule == null){
			throw new FunctionException(getName(), structure);
		}

		DescriptorValue value = molecularDescriptor.calculate(molecule);

		Exception exception = value.getException();
		if(exception != null){
			throw new FunctionException(getName(), exception.toString());
		}

		Object result = getResult(id, molecularDescriptor.getDescriptorNames(), value.getValue());

		return FieldValueUtil.create(result);
	}

	private Object getResult(String id, String[] names, IDescriptorResult result){

		for(int i = 0; i < names.length; i++){

			if(!(id).equals(nameToId(names[i]))){
				continue;
			} // End if

			if(result instanceof IntegerResult){
				IntegerResult integerResult = (IntegerResult)result;

				return integerResult.intValue();
			} else

			if(result instanceof IntegerArrayResult){
				IntegerArrayResult integerArrayResult = (IntegerArrayResult)result;

				return integerArrayResult.get(i);
			} else

			if(result instanceof DoubleResult){
				DoubleResult doubleResult = (DoubleResult)result;

				return doubleResult.doubleValue();
			} else

			if(result instanceof DoubleArrayResult){
				DoubleArrayResult doubleArrayResult = (DoubleArrayResult)result;

				return doubleArrayResult.get(i);
			}
		}

		throw new FunctionException(getName(), id);
	}

	/**
	 * Behave identically with CDK Descriptor GUI (https://github.com/rajarshi/cdkdescui)
	 */
	static
	private String nameToId(String name){
		return name.replace('-', '.');
	}

	static
	private IAtomContainer parseAtomContainer(String structure){
		SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());

		try {
			return prepareAtomContainer(parser.parseSmiles(structure));
		} catch(CDKException ce){
			throw new EvaluationException(ce.toString());
		}
	}

	static
	private IAtomContainer prepareAtomContainer(IAtomContainer structure) throws CDKException {

		if(!ConnectivityChecker.isConnected(structure)){
			throw new CDKException("The structure is not fully connected");
		}

		AtomContainerManipulator.percieveAtomTypesAndConfigureAtoms(structure);

		CDKHueckelAromaticityDetector.detectAromaticity(structure);

		CDKHydrogenAdder hydrogenator = CDKHydrogenAdder.getInstance(structure.getBuilder());
		hydrogenator.addImplicitHydrogens(structure);

		AtomContainerManipulator.convertImplicitToExplicitHydrogens(structure);

		return structure;
	}

	static
	private IMolecularDescriptor getMolecularDescriptor(String id){
		return CDKDescriptorFunction.molecularDescriptors.get(id);
	}

	private static final Map<String, IMolecularDescriptor> molecularDescriptors = Maps.<String, IMolecularDescriptor>newConcurrentMap();

	static {
		DescriptorEngine engine = new DescriptorEngine(IMolecularDescriptor.class, DefaultChemObjectBuilder.getInstance());

		List<IDescriptor> descriptors = engine.getDescriptorInstances();
		for(IDescriptor descriptor : descriptors){
			IMolecularDescriptor molecularDescriptor = (IMolecularDescriptor)descriptor;

			String[] names = molecularDescriptor.getDescriptorNames();
			for(int i = 0; i < names.length; i++){
				CDKDescriptorFunction.molecularDescriptors.put(nameToId(names[i]), molecularDescriptor);
			}
		}
	}

	static
	private IAtomContainer getAtomContainer(String structure){
		return CDKDescriptorFunction.atomContainerCache.getUnchecked(structure);
	}

	private static final LoadingCache<String, IAtomContainer> atomContainerCache = CacheBuilder.newBuilder()
		.expireAfterWrite(15, TimeUnit.MINUTES)
		.build(new CacheLoader<String, IAtomContainer>(){

			@Override
			public IAtomContainer load(String structure){
				return parseAtomContainer(structure);
			}
		});
}