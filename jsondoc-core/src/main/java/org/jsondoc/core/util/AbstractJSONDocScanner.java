package org.jsondoc.core.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Logger;
import org.jsondoc.core.annotation.Api;
import org.jsondoc.core.annotation.ApiAuthBasic;
import org.jsondoc.core.annotation.ApiAuthNone;
import org.jsondoc.core.annotation.ApiMethod;
import org.jsondoc.core.annotation.ApiObject;
import org.jsondoc.core.annotation.ApiParams;
import org.jsondoc.core.annotation.ApiPathParam;
import org.jsondoc.core.annotation.ApiQueryParam;
import org.jsondoc.core.pojo.ApiAuthDoc;
import org.jsondoc.core.pojo.ApiBodyObjectDoc;
import org.jsondoc.core.pojo.ApiDoc;
import org.jsondoc.core.pojo.ApiErrorDoc;
import org.jsondoc.core.pojo.ApiHeaderDoc;
import org.jsondoc.core.pojo.ApiMethodDoc;
import org.jsondoc.core.pojo.ApiObjectDoc;
import org.jsondoc.core.pojo.ApiObjectFieldDoc;
import org.jsondoc.core.pojo.ApiParamDoc;
import org.jsondoc.core.pojo.ApiParamType;
import org.jsondoc.core.pojo.ApiResponseObjectDoc;
import org.jsondoc.core.pojo.ApiVerb;
import org.jsondoc.core.pojo.ApiVersionDoc;
import org.jsondoc.core.pojo.JSONDoc;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

public abstract class AbstractJSONDocScanner implements JSONDocScanner {
	
	protected Reflections reflections = null;
	
	protected static Logger log = Logger.getLogger(JSONDocScanner.class);
	
	public abstract ApiDoc mergeApiDoc(Class<?> controller, ApiDoc apiDoc);
	
	public abstract ApiMethodDoc mergeApiMethodDoc(Method method, Class<?> controller, ApiMethodDoc apiMethodDoc);
	
	public abstract ApiParamDoc mergeApiPathParamDoc(Method method, int paramIndex, ApiParamDoc apiParamDoc);

	public abstract ApiParamDoc mergeApiQueryParamDoc(Method method, int paramIndex, ApiParamDoc apiParamDoc);
	
	/**
	 * Returns the main <code>ApiDoc</code>, containing <code>ApiMethodDoc</code> and <code>ApiObjectDoc</code> objects
	 * @return An <code>ApiDoc</code> object
	 */
	public JSONDoc getJSONDoc(String version, String basePath, List<String> packages) {
		Set<URL> urls = new HashSet<URL>();
		FilterBuilder filter = new FilterBuilder();
		
		log.debug("Found " + packages.size() + " package(s) to scan...");
		for (String pkg : packages) {
			log.debug("Adding package to JSONDoc recursive scan: " + pkg);
			urls.addAll(ClasspathHelper.forPackage(pkg));
			filter.includePackage(pkg);
		}

		reflections = new Reflections(new ConfigurationBuilder().filterInputsBy(filter).setUrls(urls));
		
		JSONDoc jsondocDoc = new JSONDoc(version, basePath);
		jsondocDoc.setApis(getApiDocsMap(reflections.getTypesAnnotatedWith(Api.class)));
		jsondocDoc.setObjects(getApiObjectsMap(reflections.getTypesAnnotatedWith(ApiObject.class)));
		
		return jsondocDoc;
	}
	
	/**
	 * Gets the API documentation for the set of classes passed as argument
	 */
	public Set<ApiDoc> getApiDocs(Set<Class<?>> classes) {
		Set<ApiDoc> apiDocs = new TreeSet<ApiDoc>();
		for (Class<?> controller : classes) {
			ApiDoc apiDoc = getApiDoc(controller);
			apiDocs.add(apiDoc);
		}
		return apiDocs;
	}
	
	/**
	 * Gets the API documentation for a single class annotated with @Api and for its methods, annotated with @ApiMethod
	 * @param controller
	 * @return
	 */
	private ApiDoc getApiDoc(Class<?> controller) {
		log.debug("Getting JSONDoc for class: " + controller.getName());
		ApiDoc apiDoc = ApiDoc.buildFromAnnotation(controller.getAnnotation(Api.class));
		apiDoc.setSupportedversions(ApiVersionDoc.build(controller));
		
		apiDoc.setAuth(getApiAuthDocForController(controller));
		apiDoc.setMethods(getApiMethodDocs(controller));
		
		apiDoc = mergeApiDoc(controller, apiDoc);
		
		return apiDoc;
	}
	
	private List<ApiMethodDoc> getApiMethodDocs(Class<?> controller) {
		List<ApiMethodDoc> apiMethodDocs = new ArrayList<ApiMethodDoc>();
		Method[] methods = controller.getMethods();
		for (Method method : methods) {
			if(method.isAnnotationPresent(ApiMethod.class)) {
				ApiMethodDoc apiMethodDoc = getApiMethodDoc(method, controller);
				apiMethodDocs.add(apiMethodDoc);
			}
			
		}
		return apiMethodDocs;
	}

	private ApiMethodDoc getApiMethodDoc(Method method, Class<?> controller) {
		ApiMethodDoc apiMethodDoc = ApiMethodDoc.buildFromAnnotation(method.getAnnotation(ApiMethod.class));
		
		apiMethodDoc.setHeaders(ApiHeaderDoc.build(method));
		apiMethodDoc.setPathparameters(getApiPathParamDocs(method));
		apiMethodDoc.setQueryparameters(getApiQueryParamDocs(method));
		apiMethodDoc.setBodyobject(ApiBodyObjectDoc.build(method));
		apiMethodDoc.setResponse(ApiResponseObjectDoc.build(method));
		apiMethodDoc.setApierrors(ApiErrorDoc.build(method));
		apiMethodDoc.setSupportedversions(ApiVersionDoc.build(method));
		apiMethodDoc.setAuth(getApiAuthDocForMethod(method, method.getDeclaringClass()));

		apiMethodDoc = mergeApiMethodDoc(method, controller, apiMethodDoc);
		
		apiMethodDoc = validateApiMethodDoc(apiMethodDoc);
		
		return apiMethodDoc;
	}
	
	/**
	 * This checks that some of the properties are correctly set to produce a meaningful documentation and a working playground. In case this does not happen
	 * an error string is added to the jsondocerrors list in ApiMethodDoc.
	 * It also checks that some properties are be set to produce a meaningful documentation. In case this does not happen
	 * an error string is added to the jsondocwarnings list in ApiMethodDoc.
	 * @param apiMethodDoc
	 * @return
	 */
	private ApiMethodDoc validateApiMethodDoc(ApiMethodDoc apiMethodDoc) {
		final String ERROR_MISSING_METHOD_PATH 				= "Missing documentation data: path";
		final String ERROR_MISSING_PATH_PARAM_NAME 			= "Missing documentation data: path parameter name";
		final String ERROR_MISSING_QUERY_PARAM_NAME 		= "Missing documentation data: query parameter name";
		final String WARN_MISSING_METHOD_PRODUCES 			= "Missing documentation data: produces";
		final String WARN_MISSING_METHOD_CONSUMES 			= "Missing documentation data: consumes";
		final String HINT_MISSING_PATH_PARAM_DESCRIPTION 	= "Add description to ApiPathParam";
		final String HINT_MISSING_QUERY_PARAM_DESCRIPTION 	= "Add description to ApiQueryParam";
		final String HINT_MISSING_METHOD_DESCRIPTION 		= "Add description to ApiMethod";
		
		if(apiMethodDoc.getPath().trim().isEmpty()) {
			apiMethodDoc.setPath(ERROR_MISSING_METHOD_PATH);
			apiMethodDoc.addJsondocerror(ERROR_MISSING_METHOD_PATH);
		}
		
		for (ApiParamDoc apiParamDoc : apiMethodDoc.getPathparameters()) {
			if(apiParamDoc.getName().trim().isEmpty()) {
				apiMethodDoc.addJsondocerror(ERROR_MISSING_PATH_PARAM_NAME);
			}
			
			if(apiParamDoc.getDescription().trim().isEmpty()) {
				apiMethodDoc.addJsondochint(HINT_MISSING_PATH_PARAM_DESCRIPTION);
			}
		}
		
		for (ApiParamDoc apiParamDoc : apiMethodDoc.getQueryparameters()) {
			if(apiParamDoc.getName().trim().isEmpty()) {
				apiMethodDoc.addJsondocerror(ERROR_MISSING_QUERY_PARAM_NAME);
			}
			
			if(apiParamDoc.getDescription().trim().isEmpty()) {
				apiMethodDoc.addJsondochint(HINT_MISSING_QUERY_PARAM_DESCRIPTION);
			}
		}
		
		if(apiMethodDoc.getProduces().isEmpty()) {
			apiMethodDoc.addJsondocwarning(WARN_MISSING_METHOD_PRODUCES);
		}
		
		if((apiMethodDoc.getVerb().equals(ApiVerb.POST) || apiMethodDoc.getVerb().equals(ApiVerb.PUT)) && apiMethodDoc.getConsumes().isEmpty()) {
			apiMethodDoc.addJsondocwarning(WARN_MISSING_METHOD_CONSUMES);
		}
		
		if(apiMethodDoc.getDescription().trim().isEmpty()) {
			apiMethodDoc.addJsondochint(HINT_MISSING_METHOD_DESCRIPTION);
		}
		
		return apiMethodDoc;
	}

	public Set<ApiParamDoc> getApiPathParamDocs(Method method) {
		Set<ApiParamDoc> docs = new LinkedHashSet<ApiParamDoc>();

		if (method.isAnnotationPresent(ApiParams.class)) {
			for (ApiPathParam apiParam : method.getAnnotation(ApiParams.class).pathparams()) {
				ApiParamDoc apiParamDoc = ApiParamDoc.buildFromAnnotation(apiParam, JSONDocTypeBuilder.build(new JSONDocType(), apiParam.clazz(), apiParam.clazz()), ApiParamType.PATH);
				docs.add(apiParamDoc);
			}
		}

		Annotation[][] parametersAnnotations = method.getParameterAnnotations();
		for (int i = 0; i < parametersAnnotations.length; i++) {
			for (int j = 0; j < parametersAnnotations[i].length; j++) {
				if (parametersAnnotations[i][j] instanceof ApiPathParam) {
					ApiPathParam annotation = (ApiPathParam) parametersAnnotations[i][j];
					ApiParamDoc apiParamDoc = ApiParamDoc.buildFromAnnotation(annotation, JSONDocTypeBuilder.build(new JSONDocType(), method.getParameterTypes()[i], method.getGenericParameterTypes()[i]), ApiParamType.PATH);

					apiParamDoc = mergeApiPathParamDoc(method, i, apiParamDoc);

					docs.add(apiParamDoc);
				}
			}
		}

		return docs;
	}
	
	public Set<ApiParamDoc> getApiQueryParamDocs(Method method) {
		Set<ApiParamDoc> docs = new LinkedHashSet<ApiParamDoc>();

		if (method.isAnnotationPresent(ApiParams.class)) {
			for (ApiQueryParam apiParam : method.getAnnotation(ApiParams.class).queryparams()) {
				ApiParamDoc apiParamDoc = ApiParamDoc.buildFromAnnotation(apiParam, JSONDocTypeBuilder.build(new JSONDocType(), apiParam.clazz(), apiParam.clazz()), ApiParamType.QUERY);
				docs.add(apiParamDoc);
			}
		}

		Annotation[][] parametersAnnotations = method.getParameterAnnotations();
		for (int i = 0; i < parametersAnnotations.length; i++) {
			for (int j = 0; j < parametersAnnotations[i].length; j++) {
				if (parametersAnnotations[i][j] instanceof ApiQueryParam) {
					ApiQueryParam annotation = (ApiQueryParam) parametersAnnotations[i][j];
					ApiParamDoc apiParamDoc = ApiParamDoc.buildFromAnnotation(annotation, JSONDocTypeBuilder.build(new JSONDocType(), method.getParameterTypes()[i], method.getGenericParameterTypes()[i]), ApiParamType.QUERY);

					apiParamDoc = mergeApiQueryParamDoc(method, i, apiParamDoc);

					docs.add(apiParamDoc);
				}
			}
		}

		return docs;
	}
	
	public Set<ApiObjectDoc> getApiObjectDocs(Set<Class<?>> classes) {
		Set<ApiObjectDoc> apiObjectDocs = new TreeSet<ApiObjectDoc>();
		for (Class<?> clazz : classes) {
			log.debug("Getting JSONDoc for class: " + clazz.getName());
			ApiObject annotation = clazz.getAnnotation(ApiObject.class);
			ApiObjectDoc apiObjectDoc = ApiObjectDoc.buildFromAnnotation(annotation, clazz);
			apiObjectDoc.setSupportedversions(ApiVersionDoc.build(clazz));
			
			apiObjectDoc = validateApiObjectDoc(apiObjectDoc);
			
			if(annotation.show()) {
				apiObjectDocs.add(apiObjectDoc);
			}
		}
		return apiObjectDocs;
	}
	
	private ApiObjectDoc validateApiObjectDoc(ApiObjectDoc apiObjectDoc) {
		final String HINT_MISSING_API_OBJECT_FIELD_DESCRIPTION 	= "Add description to field: %s";
		
		for (ApiObjectFieldDoc apiObjectFieldDoc : apiObjectDoc.getFields()) {
			if(apiObjectFieldDoc.getDescription().trim().isEmpty()) {
				apiObjectDoc.addJsondochint(String.format(HINT_MISSING_API_OBJECT_FIELD_DESCRIPTION, apiObjectFieldDoc.getName()));
			}
		}
		return apiObjectDoc;
	}
	
	public Map<String, Set<ApiDoc>> getApiDocsMap(Set<Class<?>> classes) {
		Map<String, Set<ApiDoc>> apiDocsMap = new TreeMap<String, Set<ApiDoc>>();
		Set<ApiDoc> apiDocSet = getApiDocs(classes);
		for (ApiDoc apiDoc : apiDocSet) {
			if(apiDocsMap.containsKey(apiDoc.getGroup())) {
				apiDocsMap.get(apiDoc.getGroup()).add(apiDoc);
			} else {
				Set<ApiDoc> groupedPojoDocs = new TreeSet<ApiDoc>();
				groupedPojoDocs.add(apiDoc);
				apiDocsMap.put(apiDoc.getGroup(), groupedPojoDocs);
			}
		}
		return apiDocsMap;
	}
	
	public Map<String, Set<ApiObjectDoc>> getApiObjectsMap(Set<Class<?>> classes) {
		Map<String, Set<ApiObjectDoc>> objectsMap = new TreeMap<String, Set<ApiObjectDoc>>();
		Set<ApiObjectDoc> apiObjectDocSet = getApiObjectDocs(classes); 
		for (ApiObjectDoc apiObjectDoc : apiObjectDocSet) {
			if(objectsMap.containsKey(apiObjectDoc.getGroup())) {
				objectsMap.get(apiObjectDoc.getGroup()).add(apiObjectDoc);
			} else {
				Set<ApiObjectDoc> groupedPojoDocs = new TreeSet<ApiObjectDoc>();
				groupedPojoDocs.add(apiObjectDoc);
				objectsMap.put(apiObjectDoc.getGroup(), groupedPojoDocs);
			}
		}
		return objectsMap;
	}

	protected ApiAuthDoc getApiAuthDocForController(Class<?> clazz) {
		if(clazz.isAnnotationPresent(ApiAuthNone.class)) {
			return ApiAuthDoc.buildFromApiAuthNoneAnnotation(clazz.getAnnotation(ApiAuthNone.class));
		}
		
		if(clazz.isAnnotationPresent(ApiAuthBasic.class)) {
			return ApiAuthDoc.buildFromApiAuthBasicAnnotation(clazz.getAnnotation(ApiAuthBasic.class));
		}
		
		return null;
	}
	
	protected ApiAuthDoc getApiAuthDocForMethod(Method method, Class<?> clazz) {
		if(method.isAnnotationPresent(ApiAuthNone.class)) {
			return ApiAuthDoc.buildFromApiAuthNoneAnnotation(method.getAnnotation(ApiAuthNone.class));
		}
		
		if(method.isAnnotationPresent(ApiAuthBasic.class)) {
			return ApiAuthDoc.buildFromApiAuthBasicAnnotation(method.getAnnotation(ApiAuthBasic.class));
		}
		
		return getApiAuthDocForController(clazz);
	}
	
	public static String[] enumConstantsToStringArray(Object[] enumConstants) {
		String[] sarr = new String[enumConstants.length];
		for (int i = 0; i < enumConstants.length; i++) {
			sarr[i] = String.valueOf(enumConstants[i]);
		}
		return sarr;
	}
	
}
