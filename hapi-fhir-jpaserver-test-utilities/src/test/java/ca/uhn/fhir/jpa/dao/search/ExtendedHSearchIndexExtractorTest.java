package ca.uhn.fhir.jpa.dao.search;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.BaseResourceIndexedSearchParam;
import ca.uhn.fhir.jpa.model.entity.ModelConfig;
import ca.uhn.fhir.jpa.model.entity.ResourceIndexedSearchParamToken;
import ca.uhn.fhir.jpa.model.search.CompositeSearchIndexData;
import ca.uhn.fhir.jpa.model.search.ExtendedHSearchIndexData;
import ca.uhn.fhir.jpa.searchparam.extractor.ISearchParamExtractor;
import ca.uhn.fhir.jpa.searchparam.extractor.ResourceIndexedSearchParamComposite;
import ca.uhn.fhir.jpa.searchparam.extractor.ResourceIndexedSearchParams;
import ca.uhn.fhir.jpa.searchparam.extractor.SearchParamExtractorR4;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.server.util.FhirContextSearchParamRegistry;
import ca.uhn.fhir.rest.server.util.ResourceSearchParams;
import ca.uhn.fhir.test.utilities.ITestDataBuilder;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

class ExtendedHSearchIndexExtractorTest implements ITestDataBuilder.WithSupport {
	FhirContext myFhirContext = FhirContext.forR4Cached();
	DaoConfig myDaoConfig = new DaoConfig();
	ModelConfig myModelConfig = new ModelConfig();
	FhirContextSearchParamRegistry mySearchParamRegistry = new FhirContextSearchParamRegistry(myFhirContext);
	SearchParamExtractorR4 mySearchParamExtractor = new SearchParamExtractorR4(myModelConfig, new PartitionSettings(), myFhirContext, mySearchParamRegistry);


	@Test
	void testExtract_composite_producesValues() {
		// setup
		ResourceIndexedSearchParamComposite composite = new ResourceIndexedSearchParamComposite("component-code-value-concept", "Observation.component");

		ISearchParamExtractor.SearchParamSet<BaseResourceIndexedSearchParam> codeParams = new ISearchParamExtractor.SearchParamSet<>();
		codeParams.add(new ResourceIndexedSearchParamToken(new PartitionSettings(), "Observation", "component-code", "https://example.com", "8480-6"));
		composite.addComponentIndexedSearchParams("component-code", RestSearchParameterTypeEnum.TOKEN, codeParams);

		ISearchParamExtractor.SearchParamSet<BaseResourceIndexedSearchParam> valueParams = new ISearchParamExtractor.SearchParamSet<>();
		valueParams.add(new ResourceIndexedSearchParamToken(new PartitionSettings(), "Observation", "component-value-concept", "https://example.com", "some_other_value"));
		composite.addComponentIndexedSearchParams("component-value-concept", RestSearchParameterTypeEnum.TOKEN, valueParams);

		ResourceIndexedSearchParams extractedParams = new ResourceIndexedSearchParams();
		extractedParams.myCompositeParams.add(composite);

		// run: now translate to HSearch
		ResourceSearchParams activeSearchParams = mySearchParamRegistry.getActiveSearchParams("Observation");
		ExtendedHSearchIndexExtractor extractor = new ExtendedHSearchIndexExtractor(
			myDaoConfig, myFhirContext, activeSearchParams, mySearchParamExtractor, myModelConfig);
		ExtendedHSearchIndexData indexData = extractor.extract(new Observation(), extractedParams);

		// validate
		Set<CompositeSearchIndexData> spIndexData = indexData.getSearchParamComposites().get("component-code-value-concept");
		assertThat(spIndexData, hasSize(1));
	}

	@Override
	public Support getTestDataBuilderSupport() {
		return new SupportNoDao(myFhirContext);
	}
}
