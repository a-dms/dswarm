/**
 * Copyright (C) 2013 – 2015 SLUB Dresden & Avantgarde Labs GmbH (<code@dswarm.org>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dswarm.converter.flow;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.name.Named;
import org.culturegraph.mf.exceptions.MorphDefException;
import org.culturegraph.mf.framework.ObjectPipe;
import org.culturegraph.mf.framework.StreamPipe;
import org.culturegraph.mf.framework.StreamReceiver;
import org.culturegraph.mf.morph.Metamorph;
import org.culturegraph.mf.stream.pipe.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import org.dswarm.common.types.Tuple;
import org.dswarm.converter.DMPConverterError;
import org.dswarm.converter.DMPConverterException;
import org.dswarm.converter.DMPMorphDefException;
import org.dswarm.converter.mf.stream.GDMEncoder;
import org.dswarm.converter.mf.stream.GDMModelReceiver;
import org.dswarm.converter.mf.stream.reader.JsonNodeReader;
import org.dswarm.converter.pipe.StreamUnflattener;
import org.dswarm.converter.pipe.timing.ObjectTimer;
import org.dswarm.converter.pipe.timing.StreamTimer;
import org.dswarm.converter.pipe.timing.TimerBasedFactory;
import org.dswarm.graph.json.Model;
import org.dswarm.graph.json.Predicate;
import org.dswarm.graph.json.Resource;
import org.dswarm.graph.json.ResourceNode;
import org.dswarm.init.util.DMPStatics;
import org.dswarm.persistence.DMPPersistenceException;
import org.dswarm.persistence.model.DMPObject;
import org.dswarm.persistence.model.internal.gdm.GDMModel;
import org.dswarm.persistence.model.resource.DataModel;
import org.dswarm.persistence.model.resource.UpdateFormat;
import org.dswarm.persistence.model.schema.Clasz;
import org.dswarm.persistence.model.schema.utils.ClaszUtils;
import org.dswarm.persistence.service.InternalModelService;
import org.dswarm.persistence.service.InternalModelServiceFactory;
import org.dswarm.persistence.util.DMPPersistenceUtil;
import org.dswarm.persistence.util.GDMUtil;

/**
 * Flow that executes a given set of transformations on data of a given data model.
 *
 * @author phorn
 * @author tgaengler
 * @author sreichert
 * @author polowins
 */
public class TransformationFlow {

	private static final Logger LOG               = LoggerFactory.getLogger(TransformationFlow.class);

	private final Metamorph transformer;

	private final String script;

	private final Optional<Filter> optionalSkipFilter;

	private final Optional<DataModel> outputDataModel;

	private final Provider<InternalModelServiceFactory> internalModelServiceFactoryProvider;

	private final TimerBasedFactory timerBasedFactory;

	private final Timer morphTimer;

	@Inject
	private TransformationFlow(
			final Provider<InternalModelServiceFactory> internalModelServiceFactoryProviderArg,
			@Named("Monitoring") final MetricRegistry registry,
			final TimerBasedFactory timerBasedFactory,
			@Assisted final Metamorph transformer,
			@Assisted final String scriptArg,
			@Assisted final Optional<DataModel> outputDataModelArg,
			@Assisted final Optional<Filter> optionalSkipFilterArg) {
		this.timerBasedFactory = timerBasedFactory;
		this.transformer = transformer;
		script = scriptArg == null ? "" : scriptArg;
		outputDataModel = outputDataModelArg;
		optionalSkipFilter = optionalSkipFilterArg;
		internalModelServiceFactoryProvider = internalModelServiceFactoryProviderArg;

		morphTimer = registry.timer("metamorph");
	}

	public String getScript() {

		return script;
	}

	public Future<String> applyRecord(final String record) throws DMPConverterException {

		// TODO: convert JSON string to Iterator with tuples of string + JsonNode pairs
		List<Tuple<String, JsonNode>> tuplesList = null;

		try {

			tuplesList = DMPPersistenceUtil.getJSONObjectMapper().readValue(record, new TypeReference<List<Tuple<String, JsonNode>>>() {

			});
		} catch (final JsonParseException e) {

			TransformationFlow.LOG.debug("couldn't parse the transformation result tuples to a JSON string");
		} catch (final JsonMappingException e) {

			TransformationFlow.LOG.debug("couldn't map the transformation result tuples to a JSON string");
		} catch (final IOException e) {

			TransformationFlow.LOG.debug("something went wrong while processing the transformation result tuples to a JSON string");
		}

		if (tuplesList == null) {

			final String msg = "couldn't process the transformation result tuples to a JSON string";
			TransformationFlow.LOG.debug(msg);

			final CompletableFuture<String> future = new CompletableFuture<>();
			future.completeExceptionally(new DMPConverterException(msg));
			return future;
		}

		return apply(Observable.from(tuplesList), false);
	}

	public Future<String> applyResource(final String resourcePath) throws DMPConverterException {

		try {
			return applyRecord(DMPPersistenceUtil.getResourceAsString(resourcePath));
		} catch (final IOException e) {

			final CompletableFuture<String> future = new CompletableFuture<>();
			future.completeExceptionally(new DMPConverterException(e.getMessage(), e));
			return future;
		}
	}

	// TODO: Observable String / Model / Future String
	public Future<String> apply(
			final Observable<Tuple<String, JsonNode>> tuples,
			final ObjectPipe<Tuple<String, JsonNode>, StreamReceiver> opener,
			final boolean writeResultToDatahub) throws DMPConverterException {

		final Context morphContext = morphTimer.time();

		// final String recordDummy = "record";

		final StreamTimer inputTimer = timerBasedFactory.forStream("stream-input");
		final ObjectTimer gdmModelsTimer = timerBasedFactory.forObject("gdm-models");
		final StreamUnflattener unflattener = new StreamUnflattener("", DMPStatics.ATTRIBUTE_DELIMITER);
		//		final StreamJsonCollapser collapser = new StreamJsonCollapser();
		final GDMEncoder converter = new GDMEncoder(outputDataModel);

		final GDMModelReceiver writer = new GDMModelReceiver();

		final StreamPipe<StreamReceiver> starter;
		if (optionalSkipFilter.isPresent()) {

			// skip filter + input timer
			starter = opener
					.setReceiver(optionalSkipFilter.get())
					.setReceiver(inputTimer);

		} else {

			// input timer
			starter = opener.setReceiver(inputTimer);
		}

		starter
				.setReceiver(transformer)
				.setReceiver(unflattener)
				.setReceiver(converter)
				.setReceiver(gdmModelsTimer)
				.setReceiver(writer);

		// transform to FE friendly JSON => or use Model#toJSON() ;)

		final Optional<String> optionalDataModelSchemaRecordClassURI =
				getDataModelSchemaRecordClassURI();

		final String defaultRecordClassURI =
				optionalDataModelSchemaRecordClassURI.orElse(ClaszUtils.BIBO_DOCUMENT_URI);

		final ObjectMapper objectMapper =
				DMPPersistenceUtil.getJSONObjectMapper();

		final Observable<GDMTransformationState> stateObservable =
				writer.getObservable().reduce(new GDMTransformationState(objectMapper), (state, gdmModel) -> {
					if (gdmModel.getModel() == null) {
						state.addModel(gdmModel);
						return state;
					}

					final Set<String> recordURIsFromGDMModel = gdmModel.getRecordURIs();

					if (recordURIsFromGDMModel == null || recordURIsFromGDMModel.isEmpty()) {

						// skip, since it seems to look like that there are no records in the model
						return state;
					}

					state.addResources(gdmModel.getModel().getResources());

					final GDMModel finalGDMModel;

					// TODO: this a WORKAROUND to insert a default type (data model schema record class URI or bibo:Document) for records in the output data model
					if (gdmModel.getRecordClassURI() == null) {

						final String recordURI = gdmModel.getRecordURIs().iterator().next();

						final Resource recordResource = state.model.getResource(recordURI);

						if (recordResource != null) {

							// TODO check this: subject OK?
							recordResource.addStatement(new ResourceNode(recordResource.getUri()), new Predicate(GDMUtil.RDF_type),
									new ResourceNode(defaultRecordClassURI));
						}

						// re-write GDM model
						finalGDMModel = new GDMModel(gdmModel.getModel(), recordURI, defaultRecordClassURI);
					} else {

						finalGDMModel = gdmModel;
					}

					return state
							.setRecordClassUriIfUnset(finalGDMModel.getRecordClassURI())
							.addModel(finalGDMModel)
							.addRecordUris(recordURIsFromGDMModel);
				})
				.map(state -> {
					if (state.recordClassUri == null && optionalDataModelSchemaRecordClassURI.isPresent()) {
						state.setRecordClassUriIfUnset(optionalDataModelSchemaRecordClassURI.get());
					}
					return state;
				});

		final Observable<GDMTransformationState> resultObservable;

		if (writeResultToDatahub) {

			if (hasDefined(outputDataModel, DMPObject::getUuid)) {

				// write result to graph db
				final InternalModelService internalModelService = internalModelServiceFactoryProvider.get().getInternalGDMGraphService();

				resultObservable = stateObservable.map(state -> {

					try {
						internalModelService.updateObject(outputDataModel.get().getUuid(), state.finalModel(), UpdateFormat.DELTA, true);
					} catch (DMPPersistenceException e) {

						final String message = "couldn't persist the result of the transformation: " + e.getMessage();
						TransformationFlow.LOG.error(message);

						throw DMPConverterError.wrap(new DMPConverterException(message, e));
					}

					return state;
				});

			} else {

				final String message = "couldn't persist the result of the transformation, because there is no output data model assigned at this task";

				TransformationFlow.LOG.error(message);

				resultObservable = Observable.error(new DMPConverterException(message));

			}

		} else {

			resultObservable = stateObservable;
		}

		tuples.subscribe(opener::process, writer::propagateError, opener::closeStream);

		morphContext.stop();

		return resultObservable
				.map(DMPConverterError.wrapped(GDMTransformationState::jsonEncoded))
				.single()
				.toBlocking().toFuture();
	}

	public Future<String> apply(final Observable<Tuple<String, JsonNode>> tuples, final boolean writeResultToDatahub) throws DMPConverterException {

		final JsonNodeReader opener = new JsonNodeReader();

		return apply(tuples, opener, writeResultToDatahub);
	}

	static Metamorph createMorph(final Reader morphString) throws DMPMorphDefException {
		try {
			return new Metamorph(morphString);
		} catch (final MorphDefException e) {
			throw new DMPMorphDefException(e.getMessage(), e);
		}
	}

	static Filter createFilter(final Reader filterString) throws DMPMorphDefException {
		return new Filter(createMorph(filterString));
	}

	static Reader readString(final String string) throws DMPConverterError {
		if (string == null) {
			throw new DMPConverterError("The script string must not be null");
		}
		return new StringReader(string);
	}

	static Optional<Reader> readString(final Optional<String> string) throws DMPConverterException {
		return string.map(TransformationFlow::readString);
	}

	static Reader readFile(final File file) throws DMPConverterException {
		try {
			return Files
					.asCharSource(file, StandardCharsets.UTF_8)
					.openBufferedStream();
		} catch (final IOException e) {
			throw new DMPConverterException("Could not read script file", e);
		}
	}

	static Reader readResource(final String path) throws DMPConverterException {
		try {
			return Resources
					.asCharSource(Resources.getResource(path), StandardCharsets.UTF_8)
					.openBufferedStream();
		} catch (final IOException e) {
			throw new DMPConverterException("Could not read script resource", e);
		}
	}

	private Optional<String> getDataModelSchemaRecordClassURI() {
		return outputDataModel.flatMap(dataModel ->
				Optional.ofNullable(dataModel.getSchema()).flatMap(schema ->
						Optional.ofNullable(schema.getRecordClass())
								.map(Clasz::getUri)));
	}

	private static <T> boolean exists(final Optional<T> optional, final java.util.function.Predicate<T> predicate) {
		return optional.isPresent() && predicate.test(optional.get());
	}

	private static <T, U> boolean hasDefined(final Optional<T> optional, final Function<T, U> access) {
		return exists(optional, t -> access.apply(t) != null);
	}

	private static final class GDMTransformationState {
		private final ObjectMapper objectMapper;
		private final Set<String> recordURIs = Sets.newLinkedHashSet();
		private final List<GDMModel> finalGDMModels = Lists.newArrayList();
		private final Model model = new Model();
		private String recordClassUri;
		private GDMModel finalModel;

		GDMTransformationState(final ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		GDMTransformationState addModel(final GDMModel gdmModel) {
			finalGDMModels.add(gdmModel);
			return this;
		}

		GDMTransformationState addResources(final Iterable<Resource> resources) {
			resources.forEach(model::addResource);
			return this;
		}

		GDMTransformationState addRecordUris(final Collection<String> recordURIs) {
			this.recordURIs.addAll(recordURIs);
			return this;
		}

		GDMTransformationState setRecordClassUriIfUnset(final String recordClassUri) {
			if (this.recordClassUri == null) {
				this.recordClassUri = recordClassUri;
			}
			return this;
		}

		GDMModel finalModel() {
			if (finalModel == null) {
				// note: we may don't really need the record class uri here (I guess), because we can provide the record identifiers
				// separately
				finalModel = new GDMModel(model, null, recordClassUri);
				finalModel.setRecordURIs(recordURIs);
			}

			return finalModel;
		}

		String jsonEncoded() throws DMPConverterException {
			final GDMModel model = finalModel();
			final JsonNode json = model.toJSON();

			try {
				return objectMapper.writeValueAsString(json);
			} catch (final JsonProcessingException e) {
				throw new DMPConverterException(e.getMessage(), e);
			}
		}
	}
}
