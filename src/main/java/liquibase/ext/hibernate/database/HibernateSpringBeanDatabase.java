package liquibase.ext.hibernate.database;

import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.ext.hibernate.database.connection.HibernateConnection;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.spi.Bootstrap;
import org.hibernate.service.ServiceRegistry;
import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.ManagedProperties;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager;
import org.springframework.orm.jpa.persistenceunit.SmartPersistenceUnitInfo;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceUnitInfo;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * Database implementation for "spring" hibernate configurations.
 * This supports passing a spring XML file reference and bean name or a package containing hibernate annotated classes.
 */
public class HibernateSpringBeanDatabase extends HibernateDatabase {

    private BeanDefinition beanDefinition;
    private ManagedProperties beanDefinitionProperties;

    public boolean isCorrectDatabaseImplementation(DatabaseConnection conn) throws DatabaseException {
        return conn.getURL().startsWith("hibernate:spring:");
    }

    @Override
    protected Metadata generateMetadata() throws DatabaseException {
        loadBeanDefinition();
        return super.generateMetadata();
    }


    @Override
    public String getProperty(String name) {
        String value = super.getProperty(name);
        if (value == null && beanDefinitionProperties != null) {
            for (Map.Entry entry : ((ManagedProperties) beanDefinition.getPropertyValues().getPropertyValue("hibernateProperties").getValue()).entrySet()) {
                if (entry.getKey() instanceof TypedStringValue && entry.getValue() instanceof TypedStringValue) {
                    if (((TypedStringValue) entry.getKey()).getValue().equals(name)) {
                        return ((TypedStringValue) entry.getValue()).getValue();
                    }
                }
            }

            value = beanDefinitionProperties.getProperty(name);
        }
        return value;
    }

    /**
     * Parse the given URL assuming it is a spring XML file
     */
    protected void loadBeanDefinition() throws DatabaseException {
        // Read configuration
        BeanDefinitionRegistry registry = new SimpleBeanDefinitionRegistry();
        XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(registry);
        reader.setNamespaceAware(true);
        HibernateConnection connection = getHibernateConnection();
        reader.loadBeanDefinitions(new ClassPathResource(connection.getPath()));

        Properties props = connection.getProperties();
        Class<? extends LocalSessionFactoryBean> beanClass = LocalSessionFactoryBean.class;

        String beanName = props.getProperty("bean", null);
        String beanClassName = props.getProperty("beanClass", null);

        if (beanClassName != null) {
            beanClass = findClass(beanClassName, beanClass);
        }

        if (beanName == null) {
            throw new IllegalStateException("A 'bean' name is required, matching a '" + beanClassName + "' definition in '" + connection.getPath() + "'.");
        }

        beanDefinition = registry.getBeanDefinition(beanName);
        if (beanDefinition == null) {
            throw new IllegalStateException("A bean named '" + beanName + "' could not be found in '" + connection.getPath() + "'.");
        }

        beanDefinitionProperties = (ManagedProperties) beanDefinition.getPropertyValues().getPropertyValue("hibernateProperties").getValue();
    }

    @Override
    protected void addToSources(MetadataSources sources) throws DatabaseException {
        MutablePropertyValues properties = beanDefinition.getPropertyValues();

        // Add annotated classes list.
        PropertyValue annotatedClassesProperty = properties.getPropertyValue("annotatedClasses");
        if (annotatedClassesProperty != null) {
            List<TypedStringValue> annotatedClasses = (List<TypedStringValue>) annotatedClassesProperty.getValue();
            if (annotatedClasses != null) {
                for (TypedStringValue className : annotatedClasses) {
                    LOG.info("Found annotated class " + className.getValue());
                    sources.addAnnotatedClass(findClass(className.getValue()));
                }
            }
        }

        try {
            // Add mapping locations
            PropertyValue mappingLocationsProp = properties.getPropertyValue("mappingLocations");
            if (mappingLocationsProp != null) {
                List<TypedStringValue> mappingLocations = (List<TypedStringValue>) mappingLocationsProp.getValue();
                if (mappingLocations != null) {
                    ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
                    for (TypedStringValue mappingLocation : mappingLocations) {
                        LOG.info("Found mappingLocation " + mappingLocation.getValue());
                        Resource[] resources = resourcePatternResolver.getResources(mappingLocation.getValue());
                        for (int i = 0; i < resources.length; i++) {
                            URL url = resources[i].getURL();
                            LOG.info("Adding resource  " + url);
                            sources.addURL(url);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof DatabaseException) {
                throw (DatabaseException) e;
            } else {
                throw new DatabaseException(e);
            }
        }
    }

    private Class<?> findClass(String className) {
        return findClass(className, Object.class);
    }

    private <T> Class<? extends T> findClass(String className, Class<T> superClass) {
        try {
            Class<?> newClass = Class.forName(className);
            if (superClass.isAssignableFrom(newClass)) {
                return newClass.asSubclass(superClass);
            } else {
                throw new IllegalStateException("The provided class '" + className + "' is not assignable from the '" + superClass.getName() + "' superclass.");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to find required class: '" + className + "'. Please check classpath and class name.");
        }
    }

    @Override
    public String getShortName() {
        return "hibernateSpringBean";
    }

    @Override
    protected String getDefaultDatabaseProductName() {
        return "Hibernate Spring Bean";
    }

}