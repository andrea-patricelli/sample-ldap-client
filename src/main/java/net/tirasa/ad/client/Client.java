package net.tirasa.ad.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private static String AD_URL;

    private static String AD_AUTH_TYPE;

    private static String AD_PRINCIPAL;

    private static String AD_PRINCIPAL_PASSWORD;

    private static String AD_ROOT_SUFFIX;

    static {
        InputStream configuration = null;
        BufferedReader propsFileReader = null;
        try {
            File file = new File(System.getProperty("user.dir"), "connection.properties");
            propsFileReader = new BufferedReader(new FileReader(file));
            LOG.info("Found configuration file in [{}]. Using it.",
                    System.getProperty("user.dir") + "/connection.properties");
        } catch (FileNotFoundException e) {
            LOG.info("Configuration file not found in [{}]. Fallback to default one.",
                    System.getProperty("user.dir") + "/connection.properties");
            configuration = Client.class.getResourceAsStream("/connection.properties");
        }
        final Properties prop = new Properties();
        try {
            if (propsFileReader == null) {
                prop.load(configuration);
            } else {
                prop.load(propsFileReader);
            }
            AD_URL = prop.getProperty("ad.url");
            AD_AUTH_TYPE = prop.getProperty("ad.auth.type");
            AD_PRINCIPAL = prop.getProperty("ad.principal");
            AD_PRINCIPAL_PASSWORD = prop.getProperty("ad.principal.password");
            AD_ROOT_SUFFIX = prop.getProperty("ad.root.suffix");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read from configuration.properties", e);
        } finally {
            if (configuration != null) {
                try {
                    configuration.close();
                } catch (IOException ignore) {
                    //ignore
                }
            }
        }
    }

    public static void main(String[] args) {

        if (args.length == 0) {
            LOG.error("Must provide csv path");
            throw new IllegalArgumentException("Must provide csv path");
        }

        String csvPath = args[0];
        boolean dryRun = args.length > 1 ? Boolean.valueOf(args[1]) : true;

        LOG.info("Running on file [{}] dry run [{}]", csvPath, dryRun);
        LOG.debug("AD params are [{}] [{}] [{}]", AD_URL, AD_AUTH_TYPE, AD_PRINCIPAL);

        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setTimeLimit(30000);

        final Optional<InitialDirContext> ctxOpt = Optional.empty();
        try {
            InitialDirContext ctx = ctxOpt.orElse(getADResourceDirContext());
            CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(new FileReader(csvPath));
            parser.getRecords().forEach(csvRecord -> {
                String codiceFiscale = csvRecord.get(0).trim();
                LOG.info("Processing user [{}]", codiceFiscale);
                String query = String.format("(&(employeeID=%s)(objectclass=user))", codiceFiscale);
                try {
                    NamingEnumeration<SearchResult> search = ctx.search(AD_ROOT_SUFFIX, query, searchControls);
                    if (search.hasMore()) {
                        SearchResult searchResult = search.next();
                        Attribute sAMAccountName = searchResult.getAttributes().get("sAMAccountName");
                        Attribute objectDn = searchResult.getAttributes().get("distinguishedName");
                        LOG.info("Found user [{}] with sAMAccountName [{}]", objectDn.get(), sAMAccountName.get());
                        if (StringUtils.equals((String) sAMAccountName.get(), codiceFiscale)) {
                            LOG.warn("No need to update sAMAccountName for user [{}] [{}] [{}]",
                                    searchResult.getName(),
                                    objectDn.get(),
                                    codiceFiscale);
                        } else {
                            LOG.info("Updating [{}]" + (dryRun ? "DRY RUN" : StringUtils.EMPTY), objectDn);
                            if (!dryRun) {
                                updateLdapRemoteObject(ctx,
                                        (String) objectDn.get(),
                                        Pair.of("sAMAccountName", codiceFiscale));
                            }
                        }
                    } else {
                        LOG.warn("User [{}] not found", codiceFiscale);
                    }
                } catch (NamingException e) {
                    LOG.error("Unable to run AD client on [{}]", codiceFiscale, e);
                }
            });
        } catch (Exception e) {
            LOG.error("Unable to run AD client", e);
        } finally {
            if (ctxOpt.isPresent()) {
                try {
                    ctxOpt.get().close();
                } catch (NamingException e) {
                    // ignore
                }
            }
        }
    }

    protected static InitialDirContext getADResourceDirContext() throws NamingException {
        Properties env = new Properties();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, AD_URL);
        env.put(Context.SECURITY_AUTHENTICATION, AD_AUTH_TYPE);
        env.put(Context.SECURITY_PRINCIPAL, AD_PRINCIPAL);
        env.put(Context.SECURITY_CREDENTIALS, AD_PRINCIPAL_PASSWORD);
        env.put("java.naming.ldap.factory.socket", TrustAllCertsSocketFactory.class.getName());

        return new InitialDirContext(env);
    }

    private static void updateLdapRemoteObject(InitialDirContext ctx,
            final String objectDn,
            final Pair<String, String> attribute) {

        try {
            Attribute ldapAttribute = new BasicAttribute(attribute.getKey(), attribute.getValue());
            ModificationItem[] item = new ModificationItem[1];
            item[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, ldapAttribute);
            ctx.modifyAttributes(objectDn, item);
        } catch (Exception e) {
            LOG.error("Unable to update [{}]", objectDn, e);
        }
    }
}
