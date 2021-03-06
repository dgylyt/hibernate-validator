/*
 * Hibernate Validator, declare and validate application constraints
 *
 * License: Apache License, Version 2.0
 * See the license.txt file in the root directory or <http://www.apache.org/licenses/LICENSE-2.0>.
 */
package org.hibernate.validator.internal.engine;

import static org.hibernate.validator.internal.util.CollectionHelper.newArrayList;
import static org.hibernate.validator.internal.util.CollectionHelper.newHashSet;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.validation.ClockProvider;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.ParameterNameProvider;
import javax.validation.TraversableResolver;
import javax.validation.Validator;
import javax.validation.spi.ConfigurationState;

import org.hibernate.validator.HibernateValidatorConfiguration;
import org.hibernate.validator.HibernateValidatorContext;
import org.hibernate.validator.HibernateValidatorFactory;
import org.hibernate.validator.cfg.ConstraintMapping;
import org.hibernate.validator.internal.cfg.context.DefaultConstraintMapping;
import org.hibernate.validator.internal.engine.MethodValidationConfiguration.Builder;
import org.hibernate.validator.internal.engine.constraintdefinition.ConstraintDefinitionContribution;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager;
import org.hibernate.validator.internal.engine.groups.ValidationOrderGenerator;
import org.hibernate.validator.internal.engine.scripting.DefaultScriptEvaluatorFactory;
import org.hibernate.validator.internal.engine.valueextraction.ValueExtractorManager;
import org.hibernate.validator.internal.metadata.BeanMetaDataManager;
import org.hibernate.validator.internal.metadata.core.ConstraintHelper;
import org.hibernate.validator.internal.metadata.provider.MetaDataProvider;
import org.hibernate.validator.internal.metadata.provider.ProgrammaticMetaDataProvider;
import org.hibernate.validator.internal.metadata.provider.XmlMetaDataProvider;
import org.hibernate.validator.internal.util.ExecutableHelper;
import org.hibernate.validator.internal.util.ExecutableParameterNameProvider;
import org.hibernate.validator.internal.util.StringHelper;
import org.hibernate.validator.internal.util.TypeResolutionHelper;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;
import org.hibernate.validator.internal.util.privilegedactions.GetClassLoader;
import org.hibernate.validator.internal.util.privilegedactions.LoadClass;
import org.hibernate.validator.internal.util.privilegedactions.NewInstance;
import org.hibernate.validator.internal.util.stereotypes.Immutable;
import org.hibernate.validator.internal.util.stereotypes.ThreadSafe;
import org.hibernate.validator.spi.cfg.ConstraintMappingContributor;
import org.hibernate.validator.spi.scripting.ScriptEvaluatorFactory;

/**
 * Factory returning initialized {@code Validator} instances. This is the Hibernate Validator default
 * implementation of the {@code ValidatorFactory} interface.
 *
 * @author Emmanuel Bernard
 * @author Hardy Ferentschik
 * @author Gunnar Morling
 * @author Kevin Pollet &lt;kevin.pollet@serli.com&gt; (C) 2011 SERLI
 * @author Chris Beckey &lt;cbeckey@paypal.com&gt;
 * @author Guillaume Smet
 */
public class ValidatorFactoryImpl implements HibernateValidatorFactory {

	private static final Log LOG = LoggerFactory.make( MethodHandles.lookup() );

	/**
	 * The default message interpolator for this factory.
	 */
	private final MessageInterpolator messageInterpolator;

	/**
	 * The default traversable resolver for this factory.
	 */
	private final TraversableResolver traversableResolver;

	/**
	 * The default parameter name provider for this factory.
	 */
	private final ExecutableParameterNameProvider parameterNameProvider;

	/**
	 * Provider for the current time when validating {@code @Future} or {@code @Past}
	 */
	private final ClockProvider clockProvider;

	/**
	 * Used to get the {@code ScriptEvaluatorFactory} when validating {@code @ScriptAssert} and
	 * {@code @ParameterScriptAssert} constraints.
	 */
	private final ScriptEvaluatorFactory scriptEvaluatorFactory;

	/**
	 * The constraint validator manager for this factory.
	 */
	private final ConstraintValidatorManager constraintValidatorManager;

	/**
	 * Programmatic constraints passed via the Hibernate Validator specific API. Empty if there are
	 * no programmatic constraints
	 */
	@Immutable
	private final Set<DefaultConstraintMapping> constraintMappings;

	/**
	 * Helper for dealing with built-in validators and determining custom constraint annotations.
	 */
	private final ConstraintHelper constraintHelper;

	/**
	 * Used for resolving type parameters. Thread-safe.
	 */
	private final TypeResolutionHelper typeResolutionHelper;

	/**
	 * Used for discovering overridden methods. Thread-safe.
	 */
	private final ExecutableHelper executableHelper;

	/**
	 * Hibernate Validator specific flag to abort validation on first constraint violation.
	 */
	private final boolean failFast;

	/**
	 * Hibernate Validator specific flags to relax constraints on parameters.
	 */
	private final MethodValidationConfiguration methodValidationConfiguration;

	/**
	 * Hibernate Validator specific flag to disable the {@code TraversableResolver} result cache.
	 */
	private final boolean traversableResolverResultCacheEnabled;

	/**
	 * Metadata provider for XML configuration.
	 */
	private final XmlMetaDataProvider xmlMetaDataProvider;

	/**
	 * Prior to the introduction of {@code ParameterNameProvider} all the bean meta data was static and could be
	 * cached for all created {@code Validator}s. {@code ParameterNameProvider} makes parts of the meta data and
	 * Bean Validation element descriptors dynamic, since depending of the used provider different parameter names
	 * could be used. To still have the metadata static we create a {@code BeanMetaDataManager} per parameter name
	 * provider. See also HV-659.
	 */
	@ThreadSafe
	private final ConcurrentMap<BeanMetaDataManagerKey, BeanMetaDataManager> beanMetaDataManagers;

	private final ValueExtractorManager valueExtractorManager;

	public ValidatorFactoryImpl(ConfigurationState configurationState) {
		ClassLoader externalClassLoader = getExternalClassLoader( configurationState );

		this.messageInterpolator = configurationState.getMessageInterpolator();
		this.traversableResolver = configurationState.getTraversableResolver();
		this.parameterNameProvider = new ExecutableParameterNameProvider( configurationState.getParameterNameProvider() );
		this.clockProvider = configurationState.getClockProvider();
		this.valueExtractorManager = new ValueExtractorManager( configurationState.getValueExtractors() );
		this.beanMetaDataManagers = new ConcurrentHashMap<>();
		this.constraintHelper = new ConstraintHelper();
		this.typeResolutionHelper = new TypeResolutionHelper();
		this.executableHelper = new ExecutableHelper( typeResolutionHelper );

		boolean tmpFailFast = false;
		boolean tmpTraversableResolverResultCacheEnabled = true;
		boolean tmpAllowOverridingMethodAlterParameterConstraint = false;
		boolean tmpAllowMultipleCascadedValidationOnReturnValues = false;
		boolean tmpAllowParallelMethodsDefineParameterConstraints = false;

		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateSpecificConfig = (ConfigurationImpl) configurationState;

			// check whether fail fast is programmatically enabled
			tmpFailFast = hibernateSpecificConfig.getFailFast();

			tmpAllowOverridingMethodAlterParameterConstraint =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowOverridingMethodAlterParameterConstraint();
			tmpAllowMultipleCascadedValidationOnReturnValues =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowMultipleCascadedValidationOnReturnValues();
			tmpAllowParallelMethodsDefineParameterConstraints =
					hibernateSpecificConfig.getMethodValidationConfiguration()
							.isAllowParallelMethodsDefineParameterConstraints();

			tmpTraversableResolverResultCacheEnabled = hibernateSpecificConfig.isTraversableResolverResultCacheEnabled();
		}

		// HV-302; don't load XmlMappingParser if not necessary
		if ( configurationState.getMappingStreams().isEmpty() ) {
			this.xmlMetaDataProvider = null;
		}
		else {
			this.xmlMetaDataProvider = new XmlMetaDataProvider(
					constraintHelper, typeResolutionHelper, valueExtractorManager, configurationState.getMappingStreams(), externalClassLoader
			);
		}

		this.constraintMappings = Collections.unmodifiableSet(
				getConstraintMappings(
						typeResolutionHelper,
						configurationState,
						externalClassLoader
				)
		);

		registerCustomConstraintValidators( constraintMappings, constraintHelper );

		Map<String, String> properties = configurationState.getProperties();

		tmpFailFast = checkPropertiesForFailFast( properties, tmpFailFast );
		this.failFast = tmpFailFast;

		Builder methodValidationConfigurationBuilder = new MethodValidationConfiguration.Builder();

		tmpAllowOverridingMethodAlterParameterConstraint = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_PARAMETER_CONSTRAINT_OVERRIDE,
				tmpAllowOverridingMethodAlterParameterConstraint
		);
		methodValidationConfigurationBuilder.allowOverridingMethodAlterParameterConstraint(
				tmpAllowOverridingMethodAlterParameterConstraint
		);

		tmpAllowMultipleCascadedValidationOnReturnValues = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_MULTIPLE_CASCADED_VALIDATION_ON_RESULT,
				tmpAllowMultipleCascadedValidationOnReturnValues
		);
		methodValidationConfigurationBuilder.allowMultipleCascadedValidationOnReturnValues(
				tmpAllowMultipleCascadedValidationOnReturnValues
		);

		tmpAllowParallelMethodsDefineParameterConstraints = checkPropertiesForBoolean(
				properties,
				HibernateValidatorConfiguration.ALLOW_PARALLEL_METHODS_DEFINE_PARAMETER_CONSTRAINTS,
				tmpAllowParallelMethodsDefineParameterConstraints
		);
		methodValidationConfigurationBuilder.allowParallelMethodsDefineParameterConstraints(
				tmpAllowParallelMethodsDefineParameterConstraints
		);
		this.methodValidationConfiguration = methodValidationConfigurationBuilder.build();

		tmpTraversableResolverResultCacheEnabled = checkPropertiesForBoolean( properties,
				HibernateValidatorConfiguration.ENABLE_TRAVERSABLE_RESOLVER_RESULT_CACHE, tmpTraversableResolverResultCacheEnabled );
		this.traversableResolverResultCacheEnabled = tmpTraversableResolverResultCacheEnabled;

		this.constraintValidatorManager = new ConstraintValidatorManager( configurationState.getConstraintValidatorFactory() );

		this.scriptEvaluatorFactory = getScriptEvaluatorFactory( configurationState, properties, externalClassLoader );

		if ( LOG.isDebugEnabled() ) {
			logValidatorFactoryScopedConfiguration( configurationState, this.scriptEvaluatorFactory.getClass() );
		}
	}

	private static ClassLoader getExternalClassLoader(ConfigurationState configurationState) {
		return ( configurationState instanceof ConfigurationImpl ) ? ( (ConfigurationImpl) configurationState ).getExternalClassLoader() : null;
	}

	private static Set<DefaultConstraintMapping> getConstraintMappings(TypeResolutionHelper typeResolutionHelper,
			ConfigurationState configurationState, ClassLoader externalClassLoader) {
		Set<DefaultConstraintMapping> constraintMappings = newHashSet();

		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateConfiguration = (ConfigurationImpl) configurationState;

			// programmatic config
			/* We add these first so that constraint mapping created through DefaultConstraintMappingBuilder will take
			 * these programmatically defined mappings into account when checking for constraint definition uniqueness
			 */
			constraintMappings.addAll( hibernateConfiguration.getProgrammaticMappings() );

			// service loader based config
			ConstraintMappingContributor serviceLoaderBasedContributor = new ServiceLoaderBasedConstraintMappingContributor(
					typeResolutionHelper,
					externalClassLoader != null ? externalClassLoader : run( GetClassLoader.fromContext() ) );
			DefaultConstraintMappingBuilder builder = new DefaultConstraintMappingBuilder( constraintMappings );
			serviceLoaderBasedContributor.createConstraintMappings( builder );
		}

		// XML-defined constraint mapping contributors
		List<ConstraintMappingContributor> contributors = getPropertyConfiguredConstraintMappingContributors( configurationState.getProperties(),
				externalClassLoader );

		for ( ConstraintMappingContributor contributor : contributors ) {
			DefaultConstraintMappingBuilder builder = new DefaultConstraintMappingBuilder( constraintMappings );
			contributor.createConstraintMappings( builder );
		}

		return constraintMappings;
	}

	@Override
	public Validator getValidator() {
		return createValidator(
				constraintValidatorManager.getDefaultConstraintValidatorFactory(),
				messageInterpolator,
				traversableResolver,
				parameterNameProvider,
				clockProvider,
				scriptEvaluatorFactory,
				failFast,
				valueExtractorManager,
				methodValidationConfiguration,
				traversableResolverResultCacheEnabled
		);
	}

	@Override
	public MessageInterpolator getMessageInterpolator() {
		return messageInterpolator;
	}

	@Override
	public TraversableResolver getTraversableResolver() {
		return traversableResolver;
	}

	@Override
	public ConstraintValidatorFactory getConstraintValidatorFactory() {
		return constraintValidatorManager.getDefaultConstraintValidatorFactory();
	}

	@Override
	public ParameterNameProvider getParameterNameProvider() {
		return parameterNameProvider.getDelegate();
	}

	public ExecutableParameterNameProvider getExecutableParameterNameProvider() {
		return parameterNameProvider;
	}

	@Override
	public ClockProvider getClockProvider() {
		return clockProvider;
	}

	@Override
	public ScriptEvaluatorFactory getScriptEvaluatorFactory() {
		return scriptEvaluatorFactory;
	}

	public boolean isFailFast() {
		return failFast;
	}

	MethodValidationConfiguration getMethodValidationConfiguration() {
		return methodValidationConfiguration;
	}

	public boolean isTraversableResolverResultCacheEnabled() {
		return traversableResolverResultCacheEnabled;
	}

	ValueExtractorManager getValueExtractorManager() {
		return valueExtractorManager;
	}

	@Override
	public <T> T unwrap(Class<T> type) {
		//allow unwrapping into public super types
		if ( type.isAssignableFrom( HibernateValidatorFactory.class ) ) {
			return type.cast( this );
		}
		throw LOG.getTypeNotSupportedForUnwrappingException( type );
	}

	@Override
	public HibernateValidatorContext usingContext() {
		return new ValidatorContextImpl( this );
	}

	@Override
	public void close() {
		constraintValidatorManager.clear();
		constraintHelper.clear();
		for ( BeanMetaDataManager beanMetaDataManager : beanMetaDataManagers.values() ) {
			beanMetaDataManager.clear();
		}
		scriptEvaluatorFactory.clear();
	}

	Validator createValidator(ConstraintValidatorFactory constraintValidatorFactory,
			MessageInterpolator messageInterpolator,
			TraversableResolver traversableResolver,
			ExecutableParameterNameProvider parameterNameProvider,
			ClockProvider clockProvider,
			ScriptEvaluatorFactory scriptEvaluatorFactory,
			boolean failFast,
			ValueExtractorManager valueExtractorManager,
			MethodValidationConfiguration methodValidationConfiguration,
			boolean traversableResolverResultCacheEnabled) {

		ValidationOrderGenerator validationOrderGenerator = new ValidationOrderGenerator();

		BeanMetaDataManager beanMetaDataManager = beanMetaDataManagers.computeIfAbsent(
				new BeanMetaDataManagerKey( parameterNameProvider, valueExtractorManager, methodValidationConfiguration ),
				key -> new BeanMetaDataManager(
						constraintHelper,
						executableHelper,
						typeResolutionHelper,
						parameterNameProvider,
						valueExtractorManager,
						validationOrderGenerator,
						buildDataProviders(),
						methodValidationConfiguration
				)
		 );

		return new ValidatorImpl(
				constraintValidatorFactory,
				messageInterpolator,
				traversableResolver,
				beanMetaDataManager,
				parameterNameProvider,
				clockProvider,
				scriptEvaluatorFactory,
				valueExtractorManager,
				constraintValidatorManager,
				validationOrderGenerator,
				failFast,
				traversableResolverResultCacheEnabled
		);
	}

	private List<MetaDataProvider> buildDataProviders() {
		List<MetaDataProvider> metaDataProviders = newArrayList();
		if ( xmlMetaDataProvider != null ) {
			metaDataProviders.add( xmlMetaDataProvider );
		}

		if ( !constraintMappings.isEmpty() ) {
			metaDataProviders.add(
					new ProgrammaticMetaDataProvider(
							constraintHelper,
							typeResolutionHelper,
							valueExtractorManager,
							constraintMappings
					)
			);
		}
		return metaDataProviders;
	}

	private boolean checkPropertiesForFailFast(Map<String, String> properties, boolean programmaticValue) {
		boolean value = programmaticValue;
		String propertyStringValue = properties.get( HibernateValidatorConfiguration.FAIL_FAST );
		if ( propertyStringValue != null ) {
			boolean configurationValue = Boolean.valueOf( propertyStringValue );
			// throw an exception if the programmatic value is true and it overrides a false configured value
			if ( programmaticValue && !configurationValue ) {
				throw LOG.getInconsistentFailFastConfigurationException();
			}
			value = configurationValue;
		}
		return value;
	}

	private boolean checkPropertiesForBoolean(Map<String, String> properties, String propertyKey, boolean programmaticValue) {
		boolean value = programmaticValue;
		String propertyStringValue = properties.get( propertyKey );
		if ( propertyStringValue != null ) {
			value = Boolean.valueOf( propertyStringValue );
		}
		return value;
	}

	/**
	 * Returns a list with {@link ConstraintMappingContributor}s configured via the
	 * {@link HibernateValidatorConfiguration#CONSTRAINT_MAPPING_CONTRIBUTORS} property.
	 *
	 * @param properties the properties used to bootstrap the factory
	 *
	 * @return a list with property-configured {@link ConstraintMappingContributor}s; May be empty but never {@code null}
	 */
	private static List<ConstraintMappingContributor> getPropertyConfiguredConstraintMappingContributors(
			Map<String, String> properties, ClassLoader externalClassLoader) {
		String propertyValue = properties.get( HibernateValidatorConfiguration.CONSTRAINT_MAPPING_CONTRIBUTORS );

		if ( StringHelper.isNullOrEmptyString( propertyValue ) ) {
			return Collections.emptyList();
		}

		String[] contributorNames = propertyValue.toString().split( "," );
		List<ConstraintMappingContributor> contributors = newArrayList( contributorNames.length );

		for ( String contributorName : contributorNames ) {
			@SuppressWarnings("unchecked")
			Class<? extends ConstraintMappingContributor> contributorType = (Class<? extends ConstraintMappingContributor>) run(
					LoadClass.action( contributorName, externalClassLoader ) );
			contributors.add( run( NewInstance.action( contributorType, "constraint mapping contributor class" ) ) );
		}

		return contributors;
	}

	private static ScriptEvaluatorFactory getScriptEvaluatorFactory(ConfigurationState configurationState, Map<String, String> properties,
			ClassLoader externalClassLoader) {
		if ( configurationState instanceof ConfigurationImpl ) {
			ConfigurationImpl hibernateSpecificConfig = (ConfigurationImpl) configurationState;
			if ( hibernateSpecificConfig.getScriptEvaluatorFactory() != null ) {
				LOG.usingScriptEvaluatorFactory( hibernateSpecificConfig.getScriptEvaluatorFactory().getClass() );
				return hibernateSpecificConfig.getScriptEvaluatorFactory();
			}
		}

		String scriptEvaluatorFactoryFqcn = properties.get( HibernateValidatorConfiguration.SCRIPT_EVALUATOR_FACTORY_CLASSNAME );
		if ( scriptEvaluatorFactoryFqcn != null ) {
			try {
				@SuppressWarnings("unchecked")
				Class<? extends ScriptEvaluatorFactory> clazz = (Class<? extends ScriptEvaluatorFactory>) run(
						LoadClass.action( scriptEvaluatorFactoryFqcn, externalClassLoader )
				);
				ScriptEvaluatorFactory scriptEvaluatorFactory = run( NewInstance.action( clazz, "script evaluator factory class" ) );
				LOG.usingScriptEvaluatorFactory( clazz );

				return scriptEvaluatorFactory;
			}
			catch (Exception e) {
				throw LOG.getUnableToInstantiateScriptEvaluatorFactoryClassException( scriptEvaluatorFactoryFqcn, e );
			}
		}

		return new DefaultScriptEvaluatorFactory( externalClassLoader );
	}

	private static void registerCustomConstraintValidators(Set<DefaultConstraintMapping> constraintMappings,
			ConstraintHelper constraintHelper) {
		Set<Class<?>> definedConstraints = newHashSet();
		for ( DefaultConstraintMapping constraintMapping : constraintMappings ) {
			for ( ConstraintDefinitionContribution<?> contribution : constraintMapping.getConstraintDefinitionContributions() ) {
				processConstraintDefinitionContribution( contribution, constraintHelper, definedConstraints );
			}
		}
	}

	private static <A extends Annotation> void processConstraintDefinitionContribution(
			ConstraintDefinitionContribution<A> constraintDefinitionContribution, ConstraintHelper constraintHelper,
			Set<Class<?>> definedConstraints) {
		Class<A> constraintType = constraintDefinitionContribution.getConstraintType();
		if ( definedConstraints.contains( constraintType ) ) {
			throw LOG.getConstraintHasAlreadyBeenConfiguredViaProgrammaticApiException( constraintType );
		}
		definedConstraints.add( constraintType );
		constraintHelper.putValidatorDescriptors(
				constraintType,
				constraintDefinitionContribution.getValidatorDescriptors(),
				constraintDefinitionContribution.includeExisting()
		);
	}

	private static void logValidatorFactoryScopedConfiguration(ConfigurationState configurationState,
			Class<? extends ScriptEvaluatorFactory> scriptEvaluatorFactoryClass) {
		LOG.logValidatorFactoryScopedConfiguration( configurationState.getMessageInterpolator().getClass(), "message interpolator" );
		LOG.logValidatorFactoryScopedConfiguration( configurationState.getTraversableResolver().getClass(), "traversable resolver" );
		LOG.logValidatorFactoryScopedConfiguration( configurationState.getParameterNameProvider().getClass(), "parameter name provider" );
		LOG.logValidatorFactoryScopedConfiguration( configurationState.getClockProvider().getClass(), "clock provider" );
		LOG.logValidatorFactoryScopedConfiguration( scriptEvaluatorFactoryClass, "script evaluator factory" );
	}

	/**
	 * Runs the given privileged action, using a privileged block if required.
	 * <p>
	 * <b>NOTE:</b> This must never be changed into a publicly available method to avoid execution of arbitrary
	 * privileged actions within HV's protection domain.
	 */
	private static <T> T run(PrivilegedAction<T> action) {
		return System.getSecurityManager() != null ? AccessController.doPrivileged( action ) : action.run();
	}

	/**
	 * The one and only {@link ConstraintMappingContributor.ConstraintMappingBuilder} implementation.
	 */
	private static class DefaultConstraintMappingBuilder
			implements ConstraintMappingContributor.ConstraintMappingBuilder {
		private final Set<DefaultConstraintMapping> mappings;

		public DefaultConstraintMappingBuilder(Set<DefaultConstraintMapping> mappings) {
			super();
			this.mappings = mappings;
		}

		@Override
		public ConstraintMapping addConstraintMapping() {
			DefaultConstraintMapping mapping = new DefaultConstraintMapping();
			mappings.add( mapping );
			return mapping;
		}
	}

	private static class BeanMetaDataManagerKey {
		private final ExecutableParameterNameProvider parameterNameProvider;
		private final ValueExtractorManager valueExtractorManager;
		private final MethodValidationConfiguration methodValidationConfiguration;
		private final int hashCode;

		public BeanMetaDataManagerKey(ExecutableParameterNameProvider parameterNameProvider, ValueExtractorManager valueExtractorManager, MethodValidationConfiguration methodValidationConfiguration) {
			this.parameterNameProvider = parameterNameProvider;
			this.valueExtractorManager = valueExtractorManager;
			this.methodValidationConfiguration = methodValidationConfiguration;
			this.hashCode = buildHashCode( parameterNameProvider, valueExtractorManager, methodValidationConfiguration );
		}

		private static int buildHashCode(ExecutableParameterNameProvider parameterNameProvider, ValueExtractorManager valueExtractorManager, MethodValidationConfiguration methodValidationConfiguration) {
			final int prime = 31;
			int result = 1;
			result = prime * result + ( ( methodValidationConfiguration == null ) ? 0 : methodValidationConfiguration.hashCode() );
			result = prime * result + ( ( parameterNameProvider == null ) ? 0 : parameterNameProvider.hashCode() );
			result = prime * result + ( ( valueExtractorManager == null ) ? 0 : valueExtractorManager.hashCode() );
			return result;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object obj) {
			if ( this == obj ) {
				return true;
			}
			if ( obj == null ) {
				return false;
			}
			if ( getClass() != obj.getClass() ) {
				return false;
			}
			BeanMetaDataManagerKey other = (BeanMetaDataManagerKey) obj;

			return methodValidationConfiguration.equals( other.methodValidationConfiguration ) &&
					parameterNameProvider.equals( other.parameterNameProvider ) &&
					valueExtractorManager.equals( other.valueExtractorManager );
		}

		@Override
		public String toString() {
			return "BeanMetaDataManagerKey [parameterNameProvider=" + parameterNameProvider + ", valueExtractorManager=" + valueExtractorManager
					+ ", methodValidationConfiguration=" + methodValidationConfiguration + "]";
		}
	}
}
