/*
 * Copyright (C) 2005-2011 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.solr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.text.Collator;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.ParserConfigurationException;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.dictionary.IndexTokenisationMode;
import org.alfresco.repo.search.impl.lucene.AbstractLuceneQueryParser;
import org.alfresco.repo.search.impl.lucene.LuceneQueryParser;
import org.alfresco.repo.search.impl.lucene.MultiReader;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.Period;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.repository.datatype.Duration;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.solr.client.ContentPropertyValue;
import org.alfresco.solr.client.MLTextPropertyValue;
import org.alfresco.solr.client.MultiPropertyValue;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.PropertyValue;
import org.alfresco.solr.client.StringPropertyValue;
import org.alfresco.solr.query.SolrQueryParser;
import org.alfresco.solr.tracker.CoreTracker;
import org.alfresco.solr.tracker.CoreWatcherJob;
import org.alfresco.solr.tracker.IndexHealthReport;
import org.alfresco.util.CachingDateFormat;
import org.alfresco.util.CachingDateFormat.SimpleDateFormatAndResolution;
import org.alfresco.util.GUID;
import org.alfresco.util.ISO9075;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.Field.TermVector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.OpenBitSet;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.MultiMapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.util.RefCounted;
import org.json.JSONException;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.surf.util.I18NUtil;
import org.xml.sax.SAXException;

/**
 * @author Andy
 */
public class AlfrescoCoreAdminHandler extends CoreAdminHandler
{
    /**
     * 
     */
    private static final String ARG_ACLTXID = "acltxid";

    /**
     * 
     */
    private static final String ARG_TXID = "txid";

    /**
     * 
     */
    private static final String ARG_ACLID = "aclid";

    /**
     * 
     */
    private static final String ARG_NODEID = "nodeid";

    private static final String TEST_NAMESPACE = "http://www.alfresco.org/test/solrtest";

    QName createdDate = QName.createQName(TEST_NAMESPACE, "createdDate");

    QName createdTime = QName.createQName(TEST_NAMESPACE, "createdTime");

    QName orderDouble = QName.createQName(TEST_NAMESPACE, "orderDouble");

    QName orderFloat = QName.createQName(TEST_NAMESPACE, "orderFloat");

    QName orderLong = QName.createQName(TEST_NAMESPACE, "orderLong");

    QName orderInt = QName.createQName(TEST_NAMESPACE, "orderInt");

    QName orderText = QName.createQName(TEST_NAMESPACE, "orderText");

    QName orderLocalisedText = QName.createQName(TEST_NAMESPACE, "orderLocalisedText");

    QName orderMLText = QName.createQName(TEST_NAMESPACE, "orderMLText");

    QName orderLocalisedMLText = QName.createQName(TEST_NAMESPACE, "orderLocalisedMLText");

    QName aspectWithChildren = QName.createQName(TEST_NAMESPACE, "aspectWithChildren");

    private QName testType = QName.createQName(TEST_NAMESPACE, "testType");

    private QName testSuperType = QName.createQName(TEST_NAMESPACE, "testSuperType");

    private QName testAspect = QName.createQName(TEST_NAMESPACE, "testAspect");

    // private QName testSuperAspect = QName.createQName(TEST_NAMESPACE, "testSuperAspect");

    protected final static Logger log = LoggerFactory.getLogger(AlfrescoCoreAdminHandler.class);

    Scheduler scheduler = null;

    ConcurrentHashMap<String, CoreTracker> trackers = new ConcurrentHashMap<String, CoreTracker>();

    private Date orderDate = new Date();

    private int orderTextCount = 0;

    private String[] orderNames = new String[] { "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen" };

    // Spanish- Eng, French-English, Swedish German, English
    private String[] orderLocalisedNames = new String[] { "chalina", "curioso", "llama", "luz", "peach", "péché", "pêche", "sin", "\u00e4pple", "banan", "p\u00e4ron", "orange",
            "rock", "rôle", "rose", "filler" };

    private String[] orderLocaliseMLText_de = new String[] { "Arg", "Ärgerlich", "Arm", "Assistent", "Aßlar", "Assoziation", "Udet", "Übelacker", "Uell", "Ülle", "Ueve", "Üxküll",
            "Uffenbach", "", "", "" };

    private String[] orderLocaliseMLText_fr = new String[] { "cote", "côte", "coté", "côté", "", "", "", "", "", "", "", "", "", "", "", "" };

    private String[] orderLocaliseMLText_en = new String[] { "zebra", "tiger", "rose", "rôle", "rock", "lemur", "lemonade", "lemon", "kale", "guava", "cheese", "beans",
            "bananana", "apple", "and", "aardvark" };

    private String[] orderLocaliseMLText_es = new String[] { "radio", "ráfaga", "rana", "rápido", "rastrillo", "arroz", "campo", "chihuahua", "ciudad", "limonada", "llaves",
            "luna", "", "", "", "" };

    /**
     * 
     */
    public AlfrescoCoreAdminHandler()
    {
        super();
    }

    /**
     * @param coreContainer
     */
    public AlfrescoCoreAdminHandler(CoreContainer coreContainer)
    {
        super(coreContainer);

        // TODO: pick scheduler properties from SOLR config or file ...
        try
        {
            StdSchedulerFactory factory = new StdSchedulerFactory();
            Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", "SolrTrackerScheduler");
            properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
            properties.setProperty("org.quartz.threadPool.threadCount", "3");
            properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
            properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            factory.initialize(properties);
            scheduler = factory.getScheduler();
            scheduler.start();

            // Start job to manage the tracker jobs
            // Currently just add

            JobDetail job = new JobDetail("CoreWatcher", "Solr", CoreWatcherJob.class);
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put("ADMIN_HANDLER", this);
            job.setJobDataMap(jobDataMap);
            Trigger trigger;
            try
            {
                trigger = new CronTrigger("CoreWatcherTrigger", "Solr", "0/20 * * * * ? *");
                scheduler.scheduleJob(job, trigger);
            }
            catch (ParseException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }
        catch (SchedulerException e)
        {
            e.printStackTrace();
        }

    }

    /**
     * @return the scheduler
     */
    public Scheduler getScheduler()
    {
        return scheduler;
    }

    /**
     * @return the trackers
     */
    public ConcurrentHashMap<String, CoreTracker> getTrackers()
    {
        return trackers;
    }

    protected boolean handleCustomAction(SolrQueryRequest req, SolrQueryResponse rsp)
    {
        SolrParams params = req.getParams();
        String cname = params.get(CoreAdminParams.CORE);
        String a = params.get(CoreAdminParams.ACTION);
        try
        {
            if (a.equalsIgnoreCase("TEST"))
            {
                runTests(req, rsp);
                return false;
            }
            else if (a.equalsIgnoreCase("CHECK"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    if (tracker != null)
                    {
                        tracker.setCheck(true);
                    }
                }
                else
                {
                    for (String trackerName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(trackerName);
                        tracker.setCheck(true);
                    }
                }
                return false;
            }
            else if (a.equalsIgnoreCase("NODEREPORT"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    Long dbid = null;
                    if (params.get(ARG_NODEID) != null)
                    {
                        dbid = Long.valueOf(params.get(ARG_NODEID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        report.add(cname, buildNodeReport(tracker, dbid));
                        rsp.add("report", report);

                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No dbid parameter set");
                    }
                }
                else
                {
                    Long dbid = null;
                    if (params.get(ARG_NODEID) != null)
                    {
                        dbid = Long.valueOf(params.get(ARG_NODEID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        for (String trackerName : trackers.keySet())
                        {
                            CoreTracker tracker = trackers.get(trackerName);
                            report.add(trackerName, buildNodeReport(tracker, dbid));
                        }
                        rsp.add("report", report);
                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No dbid parameter set");
                    }

                }
                return false;
            }
            else if (a.equalsIgnoreCase("ACLREPORT"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    Long aclid = null;
                    if (params.get(ARG_ACLID) != null)
                    {
                        aclid = Long.valueOf(params.get(ARG_ACLID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        report.add(cname, buildAclReport(tracker, aclid));
                        rsp.add("report", report);

                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No aclid parameter set");
                    }
                }
                else
                {
                    Long aclid = null;
                    if (params.get(ARG_ACLID) != null)
                    {
                        aclid = Long.valueOf(params.get(ARG_ACLID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        for (String trackerName : trackers.keySet())
                        {
                            CoreTracker tracker = trackers.get(trackerName);
                            report.add(trackerName, buildAclReport(tracker, aclid));
                        }
                        rsp.add("report", report);
                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No dbid parameter set");
                    }

                }
                return false;
            }
            else if (a.equalsIgnoreCase("TXREPORT"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    Long txid = null;
                    if (params.get(ARG_TXID) != null)
                    {
                        txid = Long.valueOf(params.get(ARG_TXID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        report.add(cname, buildTxReport(tracker, txid));
                        rsp.add("report", report);

                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No txid parameter set");
                    }
                }
                else
                {
                    Long txid = null;
                    if (params.get(ARG_TXID) != null)
                    {
                        txid = Long.valueOf(params.get(ARG_TXID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        for (String trackerName : trackers.keySet())
                        {
                            CoreTracker tracker = trackers.get(trackerName);
                            report.add(trackerName, buildTxReport(tracker, txid));
                        }
                        rsp.add("report", report);
                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No txid parameter set");
                    }

                }
                return false;
            }
            else if (a.equalsIgnoreCase("ACLTXREPORT"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    Long acltxid = null;
                    if (params.get(ARG_ACLTXID) != null)
                    {
                        acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        report.add(cname, buildAclTxReport(tracker, acltxid));
                        rsp.add("report", report);

                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No acltxid parameter set");
                    }
                }
                else
                {
                    Long acltxid = null;
                    if (params.get(ARG_ACLTXID) != null)
                    {
                        acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                        NamedList<Object> report = new SimpleOrderedMap<Object>();
                        for (String trackerName : trackers.keySet())
                        {
                            CoreTracker tracker = trackers.get(trackerName);
                            report.add(trackerName, buildAclTxReport(tracker, acltxid));
                        }
                        rsp.add("report", report);
                    }
                    else
                    {
                        throw new AlfrescoRuntimeException("No acltxid parameter set");
                    }

                }
                return false;
            }
            else if (a.equalsIgnoreCase("REPORT"))
            {
                if (cname != null)
                {
                    Long fromTime = null;
                    if (params.get("fromTime") != null)
                    {
                        fromTime = Long.valueOf(params.get("fromTime"));
                    }
                    Long toTime = null;
                    if (params.get("toTime") != null)
                    {
                        toTime = Long.valueOf(params.get("toTime"));
                    }
                    Long fromTx = null;
                    if (params.get("fromTx") != null)
                    {
                        fromTx = Long.valueOf(params.get("fromTx"));
                    }
                    Long toTx = null;
                    if (params.get("toTx") != null)
                    {
                        toTx = Long.valueOf(params.get("toTx"));
                    }
                    Long fromAclTx = null;
                    if (params.get("fromAclTx") != null)
                    {
                        fromAclTx = Long.valueOf(params.get("fromAclTx"));
                    }
                    Long toAclTx = null;
                    if (params.get("toAclTx") != null)
                    {
                        toAclTx = Long.valueOf(params.get("toAclTx"));
                    }

                    CoreTracker tracker = trackers.get(cname);

                    NamedList<Object> report = new SimpleOrderedMap<Object>();
                    if (tracker != null)
                    {
                        report.add(cname, buildTrackerReport(tracker, fromTx, toTx, fromAclTx, toAclTx, fromTime, toTime));
                    }
                    else
                    {
                        report.add(cname, "Core unknown");
                    }
                    rsp.add("report", report);
                }
                else
                {
                    Long fromTime = null;
                    if (params.get("fromTime") != null)
                    {
                        fromTime = Long.valueOf(params.get("fromTime"));
                    }
                    Long toTime = null;
                    if (params.get("toTime") != null)
                    {
                        toTime = Long.valueOf(params.get("toTime"));
                    }
                    Long fromTx = null;
                    if (params.get("fromTx") != null)
                    {
                        fromTx = Long.valueOf(params.get("fromTx"));
                    }
                    Long toTx = null;
                    if (params.get("toTx") != null)
                    {
                        toTx = Long.valueOf(params.get("toTx"));
                    }
                    Long fromAclTx = null;
                    if (params.get("fromAclTx") != null)
                    {
                        fromAclTx = Long.valueOf(params.get("fromAclTx"));
                    }
                    Long toAclTx = null;
                    if (params.get("toAclTx") != null)
                    {
                        toAclTx = Long.valueOf(params.get("toAclTx"));
                    }

                    NamedList<Object> report = new SimpleOrderedMap<Object>();
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        report.add(coreName, buildTrackerReport(tracker, fromTx, toTx, fromAclTx, toAclTx, fromTime, toTime));
                    }
                    rsp.add("report", report);
                }

                return false;
            }
            else if (a.equalsIgnoreCase("PURGE"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    if (params.get(ARG_TXID) != null)
                    {
                        Long txid = Long.valueOf(params.get(ARG_TXID));
                        tracker.addTransactionToPurge(txid);
                    }
                    if (params.get(ARG_ACLTXID) != null)
                    {
                        Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                        tracker.addAclChangeSetToPurge(acltxid);
                    }
                    if (params.get(ARG_NODEID) != null)
                    {
                        Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                        tracker.addNodeToPurge(nodeid);
                    }
                    if (params.get(ARG_ACLID) != null)
                    {
                        Long aclid = Long.valueOf(params.get(ARG_ACLID));
                        tracker.addAclToPurge(aclid);
                    }

                }
                else
                {
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        if (params.get(ARG_TXID) != null)
                        {
                            Long txid = Long.valueOf(params.get(ARG_TXID));
                            tracker.addTransactionToPurge(txid);
                        }
                        if (params.get(ARG_ACLTXID) != null)
                        {
                            Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                            tracker.addAclChangeSetToPurge(acltxid);
                        }
                        if (params.get(ARG_NODEID) != null)
                        {
                            Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                            tracker.addNodeToPurge(nodeid);
                        }
                        if (params.get(ARG_ACLID) != null)
                        {
                            Long aclid = Long.valueOf(params.get(ARG_ACLID));
                            tracker.addAclToPurge(aclid);
                        }
                    }
                }
                return false;
            }
            else if (a.equalsIgnoreCase("REINDEX"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    if (params.get(ARG_TXID) != null)
                    {
                        Long txid = Long.valueOf(params.get(ARG_TXID));
                        tracker.addTransactionToReindex(txid);
                    }
                    if (params.get(ARG_ACLTXID) != null)
                    {
                        Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                        tracker.addAclChangeSetToReindex(acltxid);
                    }
                    if (params.get(ARG_NODEID) != null)
                    {
                        Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                        tracker.addNodeToReindex(nodeid);
                    }
                    if (params.get(ARG_ACLID) != null)
                    {
                        Long aclid = Long.valueOf(params.get(ARG_ACLID));
                        tracker.addAclToReindex(aclid);
                    }

                }
                else
                {
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        if (params.get(ARG_TXID) != null)
                        {
                            Long txid = Long.valueOf(params.get(ARG_TXID));
                            tracker.addTransactionToReindex(txid);
                        }
                        if (params.get(ARG_ACLTXID) != null)
                        {
                            Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                            tracker.addAclChangeSetToReindex(acltxid);
                        }
                        if (params.get(ARG_NODEID) != null)
                        {
                            Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                            tracker.addNodeToReindex(nodeid);
                        }
                        if (params.get(ARG_ACLID) != null)
                        {
                            Long aclid = Long.valueOf(params.get(ARG_ACLID));
                            tracker.addAclToReindex(aclid);
                        }
                    }
                }
                return false;
            }
            else if (a.equalsIgnoreCase("INDEX"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    if (params.get(ARG_TXID) != null)
                    {
                        Long txid = Long.valueOf(params.get(ARG_TXID));
                        tracker.addTransactionToIndex(txid);
                    }
                    if (params.get(ARG_ACLTXID) != null)
                    {
                        Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                        tracker.addAclChangeSetToIndex(acltxid);
                    }
                    if (params.get(ARG_NODEID) != null)
                    {
                        Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                        tracker.addNodeToIndex(nodeid);
                    }
                    if (params.get(ARG_ACLID) != null)
                    {
                        Long aclid = Long.valueOf(params.get(ARG_ACLID));
                        tracker.addAclToIndex(aclid);
                    }

                }
                else
                {
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        if (params.get(ARG_TXID) != null)
                        {
                            Long txid = Long.valueOf(params.get(ARG_TXID));
                            tracker.addTransactionToIndex(txid);
                        }
                        if (params.get(ARG_ACLTXID) != null)
                        {
                            Long acltxid = Long.valueOf(params.get(ARG_ACLTXID));
                            tracker.addAclChangeSetToIndex(acltxid);
                        }
                        if (params.get(ARG_NODEID) != null)
                        {
                            Long nodeid = Long.valueOf(params.get(ARG_NODEID));
                            tracker.addNodeToIndex(nodeid);
                        }
                        if (params.get(ARG_ACLID) != null)
                        {
                            Long aclid = Long.valueOf(params.get(ARG_ACLID));
                            tracker.addAclToIndex(aclid);
                        }
                    }
                }
                return false;
            }
            else if (a.equalsIgnoreCase("FIX"))
            {
                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);
                    IndexHealthReport indexHealthReport = tracker.checkIndex(null, null, null, null, null, null);
                    OpenBitSet toReindex = indexHealthReport.getTxInIndexButNotInDb();
                    toReindex.or(indexHealthReport.getDuplicatedTxInIndex());
                    toReindex.or(indexHealthReport.getMissingTxFromIndex());
                    long current = -1;
                    while ((current = toReindex.nextSetBit(current + 1)) != -1)
                    {
                        tracker.addTransactionToReindex(current);
                    }
                    toReindex = indexHealthReport.getAclTxInIndexButNotInDb();
                    toReindex.or(indexHealthReport.getDuplicatedAclTxInIndex());
                    toReindex.or(indexHealthReport.getMissingAclTxFromIndex());
                    current = -1;
                    while ((current = toReindex.nextSetBit(current + 1)) != -1)
                    {
                        tracker.addAclChangeSetToReindex(current);
                    }

                }
                else
                {
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        IndexHealthReport indexHealthReport = tracker.checkIndex(null, null, null, null, null, null);
                        OpenBitSet toReindex = indexHealthReport.getTxInIndexButNotInDb();
                        toReindex.or(indexHealthReport.getDuplicatedTxInIndex());
                        toReindex.or(indexHealthReport.getMissingTxFromIndex());
                        long current = -1;
                        while ((current = toReindex.nextSetBit(current + 1)) != -1)
                        {
                            tracker.addTransactionToReindex(current);
                        }
                        toReindex = indexHealthReport.getAclTxInIndexButNotInDb();
                        toReindex.or(indexHealthReport.getDuplicatedAclTxInIndex());
                        toReindex.or(indexHealthReport.getMissingAclTxFromIndex());
                        current = -1;
                        while ((current = toReindex.nextSetBit(current + 1)) != -1)
                        {
                            tracker.addAclChangeSetToReindex(current);
                        }
                    }
                }
                return false;
            }
            else if (a.equalsIgnoreCase("SUMMARY"))
            {
                boolean reset = false;
                boolean detail = false;
                boolean hist = false;
                boolean values = false;
                if (params.get("reset") != null)
                {
                    reset = Boolean.valueOf(params.get("reset"));
                }
                if (params.get("detail") != null)
                {
                    detail = Boolean.valueOf(params.get("detail"));
                }
                if (params.get("hist") != null)
                {
                    hist = Boolean.valueOf(params.get("hist"));
                }
                if (params.get("values") != null)
                {
                    values = Boolean.valueOf(params.get("values"));
                }

                if (cname != null)
                {
                    CoreTracker tracker = trackers.get(cname);

                    NamedList<Object> report = new SimpleOrderedMap<Object>();
                    if (tracker != null)
                    {
                        addCoreSummary(cname, detail, hist, values, tracker, report);

                        if (reset)
                        {
                            tracker.getTrackerStats().reset();
                        }
                    }
                    else
                    {
                        report.add(cname, "Core unknown");
                    }
                    rsp.add("Summary", report);

                }
                else
                {
                    NamedList<Object> report = new SimpleOrderedMap<Object>();
                    for (String coreName : trackers.keySet())
                    {
                        CoreTracker tracker = trackers.get(coreName);
                        if (tracker != null)
                        {
                            addCoreSummary(cname, detail, hist, values, tracker, report);

                            if (reset)
                            {
                                tracker.getTrackerStats().reset();
                            }
                        }
                        else
                        {
                            report.add(cname, "Core unknown");
                        }
                    }
                    rsp.add("Summary", report);
                }
                return false;
            }
            else
            {
                return super.handleCustomAction(req, rsp);
            }

        }
        catch (Exception ex)
        {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Error executing implementation of admin request " + a, ex);
        }
    }

    /**
     * @param req
     * @param rsp
     */
    private void runTests(SolrQueryRequest req, SolrQueryResponse rsp)
    {
        orderDate = new Date();

        orderTextCount = 0;

        try
        {
            boolean remove = true;
            SolrParams params = req.getParams();
            if (params.get("remove") != null)
            {
                remove = Boolean.valueOf(params.get("remove"));
            }

            String name = "test-" + System.nanoTime();

            // copy core from template

            File solrHome = new File(getCoreContainer().getSolrHome());
            File templates = new File(solrHome, "templates");
            File template = new File(templates, "test");

            File newCore = new File(solrHome, name);

            copyDirectory(template, newCore, false);

            // fix configuration properties

            File config = new File(newCore, "conf/solrcore.properties");
            Properties properties = new Properties();
            properties.load(new FileInputStream(config));
            properties.setProperty("data.dir.root", newCore.getCanonicalPath());
            properties.store(new FileOutputStream(config), null);

            // add core

            CoreDescriptor dcore = new CoreDescriptor(coreContainer, name, newCore.toString());
            dcore.setCoreProperties(null);
            SolrCore core = coreContainer.create(dcore);
            coreContainer.register(name, core, false);
            rsp.add("core", core.getName());

            SolrResourceLoader loader = core.getSchema().getResourceLoader();
            String id = loader.getInstanceDir();
            AlfrescoSolrDataModel dataModel = AlfrescoSolrDataModel.getInstance(id);
            // add data

            // Root

            NodeRef rootNodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            addStoreRoot(core, dataModel, rootNodeRef, 1, 1, 1, 1);
            rsp.add("RootNode", 1);

            // 1

            NodeRef n01NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n01QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "one");
            ChildAssociationRef n01CAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, n01QName, n01NodeRef, true, 0);
            addNode(core, dataModel, 1, 2, 1, testSuperType, null, getOrderProperties(), null, "andy", new ChildAssociationRef[] { n01CAR }, new NodeRef[] { rootNodeRef },
                    new String[] { "/" + n01QName.toString() }, n01NodeRef);

            // 2

            NodeRef n02NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n02QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "two");
            ChildAssociationRef n02CAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, n02QName, n02NodeRef, true, 0);
            addNode(core, dataModel, 1, 3, 1, testSuperType, null, getOrderProperties(), null, "bob", new ChildAssociationRef[] { n02CAR }, new NodeRef[] { rootNodeRef },
                    new String[] { "/" + n02QName.toString() }, n02NodeRef);

            // 3

            NodeRef n03NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n03QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "three");
            ChildAssociationRef n03CAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, n03QName, n03NodeRef, true, 0);
            addNode(core, dataModel, 1, 4, 1, testSuperType, null, getOrderProperties(), null, "cid", new ChildAssociationRef[] { n03CAR }, new NodeRef[] { rootNodeRef },
                    new String[] { "/" + n03QName.toString() }, n03NodeRef);

            // 4

            HashMap<QName, PropertyValue> properties04 = new HashMap<QName, PropertyValue>();
            HashMap<QName, String> content04 = new HashMap<QName, String>();
            properties04.putAll(getOrderProperties());
            properties04.put(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic"), new StringPropertyValue(
                    "TEXT THAT IS INDEXED STORED AND TOKENISED ATOMICALLY KEYONE"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "text-indexed-unstored-tokenised-atomic"), new StringPropertyValue(
                    "TEXT THAT IS INDEXED STORED AND TOKENISED ATOMICALLY KEYUNSTORED"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-nonatomic"), new StringPropertyValue(
                    "TEXT THAT IS INDEXED STORED AND TOKENISED BUT NOT ATOMICALLY KEYTWO"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "int-ista"), new StringPropertyValue("1"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "long-ista"), new StringPropertyValue("2"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "float-ista"), new StringPropertyValue("3.4"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "double-ista"), new StringPropertyValue("5.6"));

            Calendar c = new GregorianCalendar();
            c.setTime(new Date(((new Date().getTime() - 10000))));
            Date testDate = c.getTime();
            properties04.put(QName.createQName(TEST_NAMESPACE, "date-ista"), new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, testDate)));
            properties04.put(QName.createQName(TEST_NAMESPACE, "datetime-ista"), new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, testDate)));
            properties04
                    .put(QName.createQName(TEST_NAMESPACE, "boolean-ista"), new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, Boolean.valueOf(true))));
            properties04.put(QName.createQName(TEST_NAMESPACE, "qname-ista"),
                    new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, QName.createQName("{wibble}wobble"))));
            properties04.put(QName.createQName(TEST_NAMESPACE, "category-ista"),
                    new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, new NodeRef(new StoreRef("proto", "id"), "CategoryId"))));
            properties04.put(QName.createQName(TEST_NAMESPACE, "noderef-ista"), new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, n01NodeRef)));
            properties04.put(QName.createQName(TEST_NAMESPACE, "path-ista"), new StringPropertyValue("/" + n03QName.toString()));
            properties04.put(QName.createQName(TEST_NAMESPACE, "locale-ista"), new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, Locale.UK)));
            properties04.put(QName.createQName(TEST_NAMESPACE, "period-ista"),
                    new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, new Period("period|12"))));
            properties04.put(QName.createQName(TEST_NAMESPACE, "null"), null);
            MultiPropertyValue list_0 = new MultiPropertyValue();
            list_0.addValue(new StringPropertyValue("one"));
            list_0.addValue(new StringPropertyValue("two"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "list"), list_0);
            MLTextPropertyValue mlText = new MLTextPropertyValue();
            mlText.addValue(Locale.ENGLISH, "banana");
            mlText.addValue(Locale.FRENCH, "banane");
            mlText.addValue(Locale.CHINESE, "香蕉");
            mlText.addValue(new Locale("nl"), "banaan");
            mlText.addValue(Locale.GERMAN, "banane");
            mlText.addValue(new Locale("el"), "μπανάνα");
            mlText.addValue(Locale.ITALIAN, "banana");
            mlText.addValue(new Locale("ja"), "バナナ");
            mlText.addValue(new Locale("ko"), "바나나");
            mlText.addValue(new Locale("pt"), "banana");
            mlText.addValue(new Locale("ru"), "банан");
            mlText.addValue(new Locale("es"), "plátano");
            properties04.put(QName.createQName(TEST_NAMESPACE, "ml"), mlText);
            MultiPropertyValue list_1 = new MultiPropertyValue();
            list_1.addValue(new StringPropertyValue("100"));
            list_1.addValue(new StringPropertyValue("anyValueAsString"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "any-many-ista"), list_1);
            MultiPropertyValue list_2 = new MultiPropertyValue();
            list_2.addValue(new ContentPropertyValue(Locale.ENGLISH, 12L, "UTF-16", "text/plain"));
            properties04.put(QName.createQName(TEST_NAMESPACE, "content-many-ista"), list_2);
            content04.put(QName.createQName(TEST_NAMESPACE, "content-many-ista"), "multicontent");

            MLTextPropertyValue mlText1 = new MLTextPropertyValue();
            mlText1.addValue(Locale.ENGLISH, "cabbage");
            mlText1.addValue(Locale.FRENCH, "chou");

            MLTextPropertyValue mlText2 = new MLTextPropertyValue();
            mlText2.addValue(Locale.ENGLISH, "lemur");
            mlText2.addValue(new Locale("ru"), "лемур");

            MultiPropertyValue list_3 = new MultiPropertyValue();
            list_3.addValue(mlText1);
            list_3.addValue(mlText2);

            properties04.put(QName.createQName(TEST_NAMESPACE, "mltext-many-ista"), list_3);

            MultiPropertyValue list_4 = new MultiPropertyValue();
            list_4.addValue(null);
            properties04.put(QName.createQName(TEST_NAMESPACE, "nullist"), list_4);

            NodeRef n04NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n04QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "four");
            ChildAssociationRef n04CAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, n04QName, n04NodeRef, true, 0);

            properties04.put(QName.createQName(TEST_NAMESPACE, "aspectProperty"), new StringPropertyValue(""));
            addNode(core, dataModel, 1, 5, 1, testType, new QName[] { testAspect }, properties04, content04, "dave", new ChildAssociationRef[] { n04CAR },
                    new NodeRef[] { rootNodeRef }, new String[] { "/" + n04QName.toString() }, n04NodeRef);

            // 5

            NodeRef n05NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n05QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "five");
            ChildAssociationRef n05CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n01NodeRef, n05QName, n05NodeRef, true, 0);
            addNode(core, dataModel, 1, 6, 1, testSuperType, null, getOrderProperties(), null, "eoin", new ChildAssociationRef[] { n05CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() }, n05NodeRef);

            // 6

            NodeRef n06NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n06QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "six");
            ChildAssociationRef n06CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n01NodeRef, n06QName, n06NodeRef, true, 0);
            addNode(core, dataModel, 1, 7, 1, testSuperType, null, getOrderProperties(), null, "fred", new ChildAssociationRef[] { n06CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n06QName.toString() }, n06NodeRef);

            // 7

            NodeRef n07NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n07QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "seven");
            ChildAssociationRef n07CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n02NodeRef, n07QName, n07NodeRef, true, 0);
            addNode(core, dataModel, 1, 8, 1, testSuperType, null, getOrderProperties(), null, "gail", new ChildAssociationRef[] { n07CAR }, new NodeRef[] { rootNodeRef,
                    n02NodeRef }, new String[] { "/" + n02QName.toString() + "/" + n07QName.toString() }, n07NodeRef);

            // 8

            NodeRef n08NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n08QName_0 = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "eight-0");
            QName n08QName_1 = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "eight-1");
            QName n08QName_2 = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "eight-2");
            ChildAssociationRef n08CAR_0 = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, n08QName_0, n08NodeRef, false, 2);
            ChildAssociationRef n08CAR_1 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n01NodeRef, n08QName_1, n08NodeRef, false, 1);
            ChildAssociationRef n08CAR_2 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n02NodeRef, n08QName_2, n08NodeRef, true, 0);

            addNode(core, dataModel, 1, 9, 1, testSuperType, null, getOrderProperties(), null, "hal", new ChildAssociationRef[] { n08CAR_0, n08CAR_1, n08CAR_2 }, new NodeRef[] {
                    rootNodeRef, rootNodeRef, n01NodeRef, rootNodeRef, n02NodeRef }, new String[] { "/" + n08QName_0, "/" + n01QName.toString() + "/" + n08QName_1.toString(),
                    "/" + n02QName.toString() + "/" + n08QName_2.toString() }, n08NodeRef);

            // 9

            NodeRef n09NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n09QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "nine");
            ChildAssociationRef n09CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n05NodeRef, n09QName, n09NodeRef, true, 0);
            addNode(core, dataModel, 1, 10, 1, testSuperType, null, getOrderProperties(), null, "ian", new ChildAssociationRef[] { n09CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n09QName }, n09NodeRef);

            // 10

            NodeRef n10NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n10QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "ten");
            ChildAssociationRef n10CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n05NodeRef, n10QName, n10NodeRef, true, 0);
            addNode(core, dataModel, 1, 11, 1, testSuperType, null, getOrderProperties(), null, "jake", new ChildAssociationRef[] { n10CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n10QName }, n10NodeRef);

            // 11

            NodeRef n11NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n11QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "eleven");
            ChildAssociationRef n11CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n05NodeRef, n11QName, n11NodeRef, true, 0);
            addNode(core, dataModel, 1, 12, 1, testSuperType, null, getOrderProperties(), null, "kara", new ChildAssociationRef[] { n11CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n11QName }, n11NodeRef);

            // 12

            NodeRef n12NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n12QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "twelve");
            ChildAssociationRef n12CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n05NodeRef, n12QName, n12NodeRef, true, 0);
            addNode(core, dataModel, 1, 13, 1, testSuperType, null, getOrderProperties(), null, "loon", new ChildAssociationRef[] { n12CAR }, new NodeRef[] { rootNodeRef,
                    n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName }, n12NodeRef);

            // 13

            NodeRef n13NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n13QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "thirteen");
            QName n13QNameLink = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "link");
            ChildAssociationRef n13CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n12NodeRef, n13QName, n13NodeRef, true, 0);
            ChildAssociationRef n13CARLink = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n02NodeRef, n13QName, n13NodeRef, false, 0);
            addNode(core, dataModel, 1, 14, 1, testSuperType, null, getOrderProperties(), null, "mike", new ChildAssociationRef[] { n13CAR, n13CARLink }, new NodeRef[] {
                    rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, rootNodeRef, n02NodeRef }, new String[] {
                    "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName, "/" + n02QName.toString() + "/" + n13QNameLink }, n13NodeRef);

            // 14

            HashMap<QName, PropertyValue> properties14 = new HashMap<QName, PropertyValue>();
            properties14.putAll(getOrderProperties());
            HashMap<QName, String> content14 = new HashMap<QName, String>();
            MLTextPropertyValue desc1 = new MLTextPropertyValue();
            desc1.addValue(Locale.ENGLISH, "Alfresco tutorial");
            desc1.addValue(Locale.US, "Alfresco tutorial");

            Date explicitCreatedDate = new Date();
            try
            {
                Thread.sleep(2000);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }

            properties14.put(ContentModel.PROP_CONTENT, new ContentPropertyValue(Locale.UK, 298L, "UTF-8", "text/plain"));
            content14.put(ContentModel.PROP_CONTENT,
                    "The quick brown fox jumped over the lazy dog and ate the Alfresco Tutorial, in pdf format, along with the following stop words;  a an and are"
                            + " as at be but by for if in into is it no not of on or such that the their then there these they this to was will with: "
                            + " and random charcters \u00E0\u00EA\u00EE\u00F0\u00F1\u00F6\u00FB\u00FF");
            properties14.put(ContentModel.PROP_DESCRIPTION, desc1);
            properties14.put(ContentModel.PROP_CREATED, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, explicitCreatedDate)));
            properties14.put(ContentModel.PROP_MODIFIED, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, explicitCreatedDate)));

            NodeRef n14NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n14QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "fourteen");
            QName n14QNameCommon = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "common");
            ChildAssociationRef n14CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n13NodeRef, n14QName, n14NodeRef, true, 0);
            ChildAssociationRef n14CAR_1 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n01NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            ChildAssociationRef n14CAR_2 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n02NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            ChildAssociationRef n14CAR_5 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n05NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            ChildAssociationRef n14CAR_6 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n06NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            ChildAssociationRef n14CAR_12 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n12NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            ChildAssociationRef n14CAR_13 = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n13NodeRef, n14QNameCommon, n14NodeRef, false, 0);
            addNode(core, dataModel, 1, 15, 1, ContentModel.TYPE_CONTENT, null, properties14, content14, "noodle", new ChildAssociationRef[] { n14CAR, n14CAR_1, n14CAR_2,
                    n14CAR_5, n14CAR_6, n14CAR_12, n14CAR_13 }, new NodeRef[] { rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, n13NodeRef },
                    new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName + "/" + n14QName,
                            "/" + n02QName.toString() + "/" + n13QNameLink + "/" + n14QName, "/" + n01QName + "/" + n14QNameCommon, "/" + n02QName + "/" + n14QNameCommon,
                            "/" + n01QName + "/" + n05QName + "/" + n14QNameCommon, "/" + n01QName + "/" + n06QName + "/" + n14QNameCommon,
                            "/" + n01QName + "/" + n05QName + "/" + n12QName + "/" + n14QNameCommon,
                            "/" + n01QName + "/" + n05QName + "/" + n12QName + "/" + n13QName + "/" + n14QNameCommon }, n14NodeRef);

            // 15

            HashMap<QName, PropertyValue> properties15 = new HashMap<QName, PropertyValue>();
            properties15.putAll(getOrderProperties());
            properties15.put(ContentModel.PROP_MODIFIED, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, explicitCreatedDate)));
            HashMap<QName, String> content15 = new HashMap<QName, String>();
            content15.put(ContentModel.PROP_CONTENT, "          ");
            NodeRef n15NodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
            QName n15QName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, "fifteen");
            ChildAssociationRef n15CAR = new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, n13NodeRef, n15QName, n15NodeRef, true, 0);
            addNode(core, dataModel, 1, 16, 1, ContentModel.TYPE_THUMBNAIL, null, properties15, content15, "ood", new ChildAssociationRef[] { n15CAR }, new NodeRef[] {
                    rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, n13NodeRef }, new String[] {
                    "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName + "/" + n15QName,
                    "/" + n02QName.toString() + "/" + n13QNameLink + "/" + n14QName }, n15NodeRef);

            // run tests

            checkRootNode(rsp, core, dataModel);
            checkPaths(rsp, core, dataModel);
            checkQNames(rsp, core, dataModel);
            checkPropertyTypes(rsp, core, dataModel, testDate, n01NodeRef.toString());
            checkType(rsp, core, dataModel);
            checkText(rsp, core, dataModel);
            checkMLText(rsp, core, dataModel);
            checkAll(rsp, core, dataModel);
            checkDataType(rsp, core, dataModel);
            checkNullAndUnset(rsp, core, dataModel);
            checkNonField(rsp, core, dataModel);
            checkRanges(rsp, core, dataModel);
            checkInternalFields(rsp, core, dataModel, n01NodeRef.toString());
            checkAuthorityFilter(rsp, core, dataModel);
            checkPaging(rsp, core, dataModel);

            testSort(rsp, core, dataModel);

            //

            testAFTS(rsp, core, dataModel);
            testAFTSandSort(rsp, core, dataModel);
            testCMIS(rsp, core, dataModel);

            int count = 0;
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++)
            {
                orderDate = new Date();
                orderTextCount = 0;
                addNode(core, dataModel, 1, 2, 1, testSuperType, null, getOrderProperties(), null, "andy", new ChildAssociationRef[] { n01CAR }, new NodeRef[] { rootNodeRef },
                        new String[] { "/" + n01QName.toString() }, n01NodeRef);
            }
            long end = System.nanoTime();
            rsp.add("Index rate (docs/s)", 100.0f / (end - start) * 1e9);

            for (int i = 0; i < 10; i++)
            {
                orderDate = new Date();
                orderTextCount = 0;

                addNode(core, dataModel, 1, 2, 1, testSuperType, null, getOrderProperties(), null, "andy", new ChildAssociationRef[] { n01CAR }, new NodeRef[] { rootNodeRef },
                        new String[] { "/" + n01QName.toString() }, n01NodeRef);
                addNode(core, dataModel, 1, 3, 1, testSuperType, null, getOrderProperties(), null, "bob", new ChildAssociationRef[] { n02CAR }, new NodeRef[] { rootNodeRef },
                        new String[] { "/" + n02QName.toString() }, n02NodeRef);
                addNode(core, dataModel, 1, 4, 1, testSuperType, null, getOrderProperties(), null, "cid", new ChildAssociationRef[] { n03CAR }, new NodeRef[] { rootNodeRef },
                        new String[] { "/" + n03QName.toString() }, n03NodeRef);
                properties04.putAll(getOrderProperties());
                addNode(core, dataModel, 1, 5, 1, testType, new QName[] { testAspect }, properties04, content04, "dave", new ChildAssociationRef[] { n04CAR },
                        new NodeRef[] { rootNodeRef }, new String[] { "/" + n04QName.toString() }, n04NodeRef);
                addNode(core, dataModel, 1, 6, 1, testSuperType, null, getOrderProperties(), null, "eoin", new ChildAssociationRef[] { n05CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() }, n05NodeRef);
                addNode(core, dataModel, 1, 7, 1, testSuperType, null, getOrderProperties(), null, "fred", new ChildAssociationRef[] { n06CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n06QName.toString() }, n06NodeRef);
                addNode(core, dataModel, 1, 8, 1, testSuperType, null, getOrderProperties(), null, "gail", new ChildAssociationRef[] { n07CAR }, new NodeRef[] { rootNodeRef,
                        n02NodeRef }, new String[] { "/" + n02QName.toString() + "/" + n07QName.toString() }, n07NodeRef);
                addNode(core, dataModel, 1, 9, 1, testSuperType, null, getOrderProperties(), null, "hal", new ChildAssociationRef[] { n08CAR_0, n08CAR_1, n08CAR_2 },
                        new NodeRef[] { rootNodeRef, rootNodeRef, n01NodeRef, rootNodeRef, n02NodeRef }, new String[] { "/" + n08QName_0,
                                "/" + n01QName.toString() + "/" + n08QName_1.toString(), "/" + n02QName.toString() + "/" + n08QName_2.toString() }, n08NodeRef);
                addNode(core, dataModel, 1, 10, 1, testSuperType, null, getOrderProperties(), null, "ian", new ChildAssociationRef[] { n09CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n09QName }, n09NodeRef);
                addNode(core, dataModel, 1, 11, 1, testSuperType, null, getOrderProperties(), null, "jake", new ChildAssociationRef[] { n10CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n10QName }, n10NodeRef);
                addNode(core, dataModel, 1, 12, 1, testSuperType, null, getOrderProperties(), null, "kara", new ChildAssociationRef[] { n11CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n11QName }, n11NodeRef);
                addNode(core, dataModel, 1, 13, 1, testSuperType, null, getOrderProperties(), null, "loon", new ChildAssociationRef[] { n12CAR }, new NodeRef[] { rootNodeRef,
                        n01NodeRef, n05NodeRef }, new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName }, n12NodeRef);
                addNode(core, dataModel, 1, 14, 1, testSuperType, null, getOrderProperties(), null, "mike", new ChildAssociationRef[] { n13CAR, n13CARLink }, new NodeRef[] {
                        rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, rootNodeRef, n02NodeRef }, new String[] {
                        "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName, "/" + n02QName.toString() + "/" + n13QNameLink }, n13NodeRef);
                properties14.putAll(getOrderProperties());
                addNode(core, dataModel, 1, 15, 1, ContentModel.TYPE_CONTENT, null, properties14, content14, "noodle", new ChildAssociationRef[] { n14CAR, n14CAR_1, n14CAR_2,
                        n14CAR_5, n14CAR_6, n14CAR_12, n14CAR_13 }, new NodeRef[] { rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, n13NodeRef },
                        new String[] { "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName + "/" + n14QName,
                                "/" + n02QName.toString() + "/" + n13QNameLink + "/" + n14QName, "/" + n01QName + "/" + n14QNameCommon, "/" + n02QName + "/" + n14QNameCommon,
                                "/" + n01QName + "/" + n05QName + "/" + n14QNameCommon, "/" + n01QName + "/" + n06QName + "/" + n14QNameCommon,
                                "/" + n01QName + "/" + n05QName + "/" + n12QName + "/" + n14QNameCommon,
                                "/" + n01QName + "/" + n05QName + "/" + n12QName + "/" + n13QName + "/" + n14QNameCommon }, n14NodeRef);
                properties14.putAll(getOrderProperties());
                addNode(core, dataModel, 1, 16, 1, ContentModel.TYPE_THUMBNAIL, null, properties15, content15, "ood", new ChildAssociationRef[] { n15CAR }, new NodeRef[] {
                        rootNodeRef, n01NodeRef, n05NodeRef, n12NodeRef, n13NodeRef }, new String[] {
                        "/" + n01QName.toString() + "/" + n05QName.toString() + "/" + n12QName + "/" + n13QName + "/" + n15QName,
                        "/" + n02QName.toString() + "/" + n13QNameLink + "/" + n14QName }, n15NodeRef);
            }

            checkRootNode(rsp, core, dataModel);
            checkPaths(rsp, core, dataModel);
            checkQNames(rsp, core, dataModel);
            checkPropertyTypes(rsp, core, dataModel, testDate, n01NodeRef.toString());
            checkType(rsp, core, dataModel);
            checkText(rsp, core, dataModel);
            checkMLText(rsp, core, dataModel);
            checkAll(rsp, core, dataModel);
            checkDataType(rsp, core, dataModel);
            checkNullAndUnset(rsp, core, dataModel);
            checkNonField(rsp, core, dataModel);
            checkRanges(rsp, core, dataModel);

            testSort(rsp, core, dataModel);

            //

            testAFTS(rsp, core, dataModel);
            testAFTSandSort(rsp, core, dataModel);
            testCMIS(rsp, core, dataModel);

            //

            testChildNameEscaping(rsp, core, dataModel, rootNodeRef);

            // remove core

            if (remove)
            {
                SolrCore done = coreContainer.remove(name);
                if (done != null)
                {
                    done.close();
                }

                deleteDirectory(newCore);
            }

        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        catch (ParserConfigurationException e)
        {
            e.printStackTrace();
        }
        catch (SAXException e)
        {
            e.printStackTrace();
        }
        catch (org.apache.lucene.queryParser.ParseException e)
        {
            e.printStackTrace();
        }

    }

    /**
     * @param rsp
     * @param core
     * @param dataModel
     * @throws IOException
     */
    private void testAFTS(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("AFS", report);

        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID desc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy and dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-lazy and -dog", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy and -dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "|lazy and |dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "|eager and |dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "|lazy and |wolf", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "|eager and |wolf", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-lazy or -dog", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-lazy or -wolf", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -wolf", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy and not dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy not dog", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy and !dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy !dog", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy and -dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy -dog", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm_content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "=cm_content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "~cm_content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:big OR cm:content:lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:big AND cm:content:lazy", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "{http://www.alfresco.org/model/content/1.0}content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "=lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content:big OR @cm:content:lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content:big AND @cm:content:lazy", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@{http://www.alfresco.org/model/content/1.0}content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "~@cm:content:big OR ~@cm:content:lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown * quick", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown * dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown * dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(0) dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(1) dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(2) dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(3) dog", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(4) dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(5) dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "brown *(6) dog", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(\"lazy\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy and dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-lazy and -dog)", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-lazy and dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy and -dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(|lazy and |dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(|eager and |dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(|lazy and |wolf)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(|eager and |wolf)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-lazy or -dog)", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-eager or -dog)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-lazy or -wolf)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(-eager or -wolf)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy and not dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy not dog)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy and !dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy !dog)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy and -dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(lazy -dog)", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm_content:(\"lazy\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(big OR lazy)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(big AND lazy)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "{http://www.alfresco.org/model/content/1.0}content:(\"lazy\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(=lazy)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content:(big) OR @cm:content:(lazy)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content:(big) AND @cm:content:(lazy)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@{http://www.alfresco.org/model/content/1.0}content:(\"lazy\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content:(~big OR ~lazy)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown * quick)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown * dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown * dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(0) dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(1) dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(2) dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(3) dog)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(4) dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(5) dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(6) dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm_content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm:content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@cm_content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "@{http://www.alfresco.org/model/content/1.0}content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "{http://www.alfresco.org/model/content/1.0}content.mimetype:\"text/plain\"", 1, null, null, null, null, null, null);
        // testQueryByHandler(report, core, "/afts", "brown..dog", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "TEXT:brown..dog", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "cm:content:brown..dog", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "", 1, null, null);

        QName qname = QName.createQName(TEST_NAMESPACE, "float\\-ista");
        testQueryByHandler(report, core, "/afts", qname + ":3.40", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":3..4", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":3..3.39", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":3..3.40", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":3.41..3.9", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":3.40..3.9", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", qname + ":[3 TO 4]", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":[3 TO 3.39]", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":[3 TO 3.4]", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":[3.41 TO 4]", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":[3.4 TO 4]", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":[3 TO 3.4>", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":<3.4 TO 4]", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":<3.4 TO 3.4>", 0, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", qname + ":(3.40)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(3..4)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(3..3.39)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(3..3.40)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(3.41..3.9)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(3.40..3.9)", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", qname + ":([3 TO 4])", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":([3 TO 3.39])", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":([3 TO 3.4])", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":([3.41 TO 4])", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":([3.4 TO 4])", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":([3 TO 3.4>)", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(<3.4 TO 4])", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", qname + ":(<3.4 TO 3.4>)", 0, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "test:float_x002D_ista:3.40", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "laz*", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "l*y", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "l??y", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "?az?", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "*zy", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"laz*\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"l*y\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"l??y\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"?az?\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"*zy\"", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "cm:content:lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:laz*", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:l*y", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:l??y", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:?az?", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:*zy", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "cm:content:\"lazy\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:\"laz*\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:\"l*y\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:\"l??y\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:\"?az?\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:\"*zy\"", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "cm:content:(lazy)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(laz*)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(l*y)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(l??y)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(?az?)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(*zy)", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "cm:content:(\"lazy\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(\"laz*\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(\"l*y\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(\"l??y\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(\"?az?\")", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:(\"*zy\")", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "lazy^2 dog^4.2", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "lazy~0.7", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:laxy~0.7", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "laxy~0.7", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "=laxy~0.7", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "~laxy~0.7", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "\"quick fox\"~0", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"quick fox\"~1", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"quick fox\"~2", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"quick fox\"~3", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "\"fox quick\"~0", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"fox quick\"~1", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"fox quick\"~2", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"fox quick\"~3", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "lazy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-lazy", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy -lazy", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy^20 -lazy", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "lazy^20 -lazy^20", 16, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "cm:content:lazy", 1, null, null, null, null, null, null);

        // testQueryByHandler(report, core, "/afts", "ANDY:lazy", 1, null, null);

        testQueryByHandler(report, core, "/afts", "content:lazy", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "+PATH:\"/app:company_home/st:sites/cm:rmtestnew1/cm:documentLibrary//*\"", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts",
                "+PATH:\"/app:company_home/st:sites/cm:rmtestnew1/cm:documentLibrary//*\" -TYPE:\"{http://www.alfresco.org/model/content/1.0}thumbnail\"", 15, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts",
                "+PATH:\"/app:company_home/st:sites/cm:rmtestnew1/cm:documentLibrary//*\" AND -TYPE:\"{http://www.alfresco.org/model/content/1.0}thumbnail\"", 0, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", "(brown *(6) dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "TEXT:(brown *(6) dog)", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "\"//.\"", 0, null, null, null, null, null, null);
        // testQueryByHandler(report, core, "/afts", "PATH", "\"//.\"", 16, null, null);
        testQueryByHandler(report, core, "/afts", "cm:content:brown", 1, null, null, null, null, null, null);
        // testQueryByHandler(report, core, "/afts", "ANDY:brown", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "andy:brown", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "ANDY", "brown", 1, null, null);
        // testQueryByHandler(report, core, "/afts", "andy", "brown", 1, null, null);

        testQueryByHandler(report, core, "/afts", "modified:*", 2, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "modified:[MIN TO NOW]", 2, null, null, null, null, null, null);

    }

    private void testSort(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Sort", report);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "ID asc", new int[] { 1, 10, 11, 12, 13, 14, 15, 16, 2, 3, 4, 5, 6, 7, 8, 9 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "ID desc", new int[] { 9, 8, 7, 6, 5, 4, 3, 2, 16, 15, 14, 13, 12, 11, 10, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "_docid_ asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "_docid_ desc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "score asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "score desc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + createdDate + " asc", new int[] { 1, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + createdDate + " desc", new int[] { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + createdTime + " asc", new int[] { 1, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + createdTime + " desc", new int[] { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_MODIFIED + " asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_MODIFIED + " desc", new int[] { 15, 16, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderDouble + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderDouble + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderFloat + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderFloat + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLong + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLong + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderInt + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderInt + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderText + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderText + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 }, null, null, null, null);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " asc", new int[] { 1, 10, 11, 2, 3, 4, 5, 13, 12, 6, 7, 8, 14, 15, 16, 9 },
                Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " desc", new int[] { 9, 16, 15, 14, 8, 7, 6, 12, 13, 5, 4, 3, 2, 11, 10, 1 },
                Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " asc", new int[] { 1, 10, 11, 2, 3, 4, 5, 13, 12, 6, 8, 7, 14, 15, 16, 9 },
                Locale.FRENCH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " desc", new int[] { 9, 16, 15, 14, 7, 8, 6, 12, 13, 5, 4, 3, 2, 11, 10, 1 },
                Locale.FRENCH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " asc", new int[] { 1, 10, 11, 2, 3, 4, 5, 13, 12, 6, 7, 8, 14, 15, 16, 9 },
                Locale.GERMAN, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " desc", new int[] { 9, 16, 15, 14, 8, 7, 6, 12, 13, 5, 4, 3, 2, 11, 10, 1 },
                Locale.GERMAN, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " asc", new int[] { 1, 11, 2, 3, 4, 5, 13, 6, 7, 8, 12, 14, 15, 16, 9, 10 },
                new Locale("sv"), null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedText + " desc", new int[] { 10, 9, 16, 15, 14, 12, 8, 7, 6, 13, 5, 4, 3, 2, 11, 1 },
                new Locale("sv"), null, null, null);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderMLText + " asc", new int[] { 1, 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 14, 16 },
                Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderMLText + " desc", new int[] { 16, 14, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15, 1 },
                Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderMLText + " asc", new int[] { 1, 14, 16, 12, 10, 8, 6, 4, 2, 3, 5, 7, 9, 11, 13, 15 },
                Locale.FRENCH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderMLText + " desc", new int[] { 15, 13, 11, 9, 7, 5, 3, 2, 4, 6, 8, 10, 12, 16, 14, 1 },
                Locale.FRENCH, null, null, null);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " asc", new int[] { 1, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2 },
                Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " desc",
                new int[] { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 1 }, Locale.ENGLISH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " asc", new int[] { 1, 16, 15, 14, 13, 12, 2, 3, 4, 5, 11, 10, 9, 8, 7, 6 },
                Locale.FRENCH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " desc",
                new int[] { 6, 7, 8, 9, 10, 11, 5, 4, 3, 2, 12, 13, 14, 15, 16, 1 }, Locale.FRENCH, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " asc", new int[] { 1, 16, 15, 2, 3, 4, 5, 6, 7, 9, 8, 10, 12, 14, 11, 13 },
                Locale.GERMAN, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " desc",
                new int[] { 13, 11, 14, 12, 10, 8, 9, 7, 6, 5, 4, 3, 2, 15, 16, 1 }, Locale.GERMAN, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " asc", new int[] { 1, 16, 15, 7, 14, 8, 9, 10, 11, 12, 13, 2, 3, 4, 5, 6 },
                new Locale("es"), null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + orderLocalisedMLText + " desc",
                new int[] { 6, 5, 4, 3, 2, 13, 12, 11, 10, 9, 8, 14, 7, 15, 16, 1 }, new Locale("es"), null, null, null);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "cabbage desc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "PARENT desc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@PARENT:PARENT desc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);

        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT + ".size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT + ".size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT + ".mimetype asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/alfresco", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT + ".mimetype desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
                12, 13, 14, 16 }, null, null, null, null);
    }

    private void testCMIS(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("CMIS", report);
        testQueryByHandler(report, core, "/cmis", "select * from cmis:document", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/cmis", "select * from cmis:document D WHERE CONTAINS(D,'lazy')", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/cmis", "SELECT * FROM cmis:document D JOIN cm:ownable O ON D.cmis:objectId = O.cmis:objectId", 0, null, null, null, null, null, null);
    }

    private void testAFTSandSort(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("AFS and Sort", report);

        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT.toString() + ".size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@" + ContentModel.PROP_CONTENT.toString() + ".size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
                12, 13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, ContentModel.PROP_CONTENT.toString() + ".size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, ContentModel.PROP_CONTENT.toString() + ".size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@cm:content.size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@cm:content.size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "cm:content.size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "cm:content.size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@content.size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "@content.size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "content.size asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 15 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "content.size desc", new int[] { 15, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16 }, null, null, null, null);
        // testQueryByHandler(report, core, "/afts", "-eager or -dog", 16,
        // "@"+ContentModel.PROP_NODE_UUID.toString()+" asc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4,
        // 3, 2, 1 });
        // testQueryByHandler(report, core, "/afts", "-eager or -dog", 16,
        // "@"+ContentModel.PROP_NODE_UUID.toString()+" desc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4,
        // 3, 2, 1 });
        // testQueryByHandler(report, core, "/afts", "-eager or -dog", 16,
        // ContentModel.PROP_NODE_UUID.toString()+" asc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3,
        // 2, 1 });
        // testQueryByHandler(report, core, "/afts", "-eager or -dog", 16,
        // ContentModel.PROP_NODE_UUID.toString()+" desc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3,
        // 2, 1 });
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@" + ContentModel.PROP_NAME.toString() + " asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14,
                4, 13, 3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@" + ContentModel.PROP_NAME.toString() + " desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16,
                12, 9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, ContentModel.PROP_NAME.toString() + " asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14, 4, 13,
                3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, ContentModel.PROP_NAME.toString() + " desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16, 12,
                9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@cm:name asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14, 4, 13, 3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@cm:name desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16, 12, 9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "cm:name asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14, 4, 13, 3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "cm:name desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16, 12, 9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@name asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14, 4, 13, 3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "@name desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16, 12, 9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "name asc", new int[] { 1, 9, 12, 16, 6, 5, 15, 10, 2, 8, 7, 11, 14, 4, 13, 3 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "-eager or -dog", 16, "name desc", new int[] { 3, 13, 4, 14, 11, 7, 8, 2, 10, 15, 5, 6, 16, 12, 9, 1 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID desc", new int[] { 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 }, null, null, null, null);
    }

    /**
     * @param core
     * @param locale
     *            TODO
     * @param rows TODO
     * @param start TODO
     * @param filter TODO
     * @param req
     * @param rsp
     * @param dataModel
     * @throws IOException
     */
    private void testQueryByHandler(NamedList report, SolrCore core, String handler, String query, int count, String sort, int[] sorted, Locale locale, Integer rows, Integer start, String... filters) throws IOException
    {
        // TODO: report to rsp

        NamedList<Object> queryReport = new SimpleOrderedMap<Object>();
        report.add(query, queryReport);

        boolean passed = true;
        boolean ordered = true;

        SolrServletRequest solrReq = new SolrServletRequest(core, null);
        SolrQueryResponse solrRsp = new SolrQueryResponse();
        SolrRequestHandler afts = core.getRequestHandler(handler);

        ModifiableSolrParams newParams = new ModifiableSolrParams(solrReq.getParams());
        newParams.set("q", query);
        if(rows != null)
        {
            newParams.set("rows", "" +rows);
            queryReport.add("Rows", rows);
        }
        else
        {
            newParams.set("rows", "" + Integer.MAX_VALUE);
        }
        if(start != null)
        {
            newParams.set("start", "" +start);
            queryReport.add("Start", start);
        }
        if (sort != null)
        {
            newParams.set("sort", sort);
            queryReport.add("Sort", sort);
        }
        if (locale != null)
        {
            newParams.set("locale", locale.toString());
            queryReport.add("Locale", locale.toString());
        }
        if(filters != null)
        {
            newParams.set("fq", filters);
            queryReport.add("Filters", filters);
        }
        // newParams.set("fq", "AUTHORITY_FILTER_FROM_JSON");
        solrReq.setParams(newParams);
        ArrayList<ContentStream> streams = new ArrayList<ContentStream>();
        // streams.add(new ContentStreamBase.StringStream("json"));
        // solrReq.setContentStreams(streams);

        afts.handleRequest(solrReq, solrRsp);

        DocSlice ds = (DocSlice) solrRsp.getValues().get("response");
        if (ds != null)
        {
            if (ds.matches() != count)
            {
                passed = false;
                ordered = false;
                queryReport.add("Expected", count);
                queryReport.add("Found", ds.matches());
            }
            else
            {
                queryReport.add("Found", ds.matches());
            }
            int sz = ds.size();

            if (sorted != null)
            {
                int[] dbids = new int[sz];
                SolrIndexSearcher searcher = solrReq.getSearcher();
                DocIterator iterator = ds.iterator();
                for (int i = 0; i < sz; i++)
                {
                    int id = iterator.nextDoc();
                    Document doc = searcher.doc(id);
                    Fieldable dbidField = doc.getFieldable("DBID");
                    dbids[i] = Integer.valueOf(dbidField.stringValue());

                    if (ordered)
                    {
                        if (dbids[i] != sorted[i])
                        {

                            ordered = false;
                            queryReport.add("Sort at " + i + " expected", sorted[i]);
                            queryReport.add("Sort at " + i + " found", dbids[i]);
                        }
                    }
                }
                if (ordered)
                {
                    queryReport.add("Order", "Passed");
                }
                else
                {
                    queryReport.add("Order", "FAILED");
                    StringBuilder builder = new StringBuilder();
                    for (int i = 0; i < dbids.length; i++)
                    {
                        if (builder.length() > 0)
                        {
                            builder.append(", ");
                        }
                        builder.append(dbids[i]);
                    }
                    queryReport.add("Sorted as ", builder.toString());
                }
            }

            if (passed)
            {
                queryReport.add("Count", "Passed");
            }
            else
            {
                queryReport.add("Count", "FAILED");
            }
        }
        else
        {
            queryReport.add("Test", "ERROR");
        }

        solrReq.close();
    }

    /**
     * @param rsp
     * @param core
     * @param dataModel
     * @throws IOException
     * @throws org.apache.lucene.queryParser.ParseException
     */
    private void testChildNameEscaping(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel, NodeRef rootNodeRef) throws IOException,
            org.apache.lucene.queryParser.ParseException
    {
        String COMPLEX_LOCAL_NAME = "\u0020\u0060\u00ac\u00a6\u0021\"\u00a3\u0024\u0025\u005e\u0026\u002a\u0028\u0029\u002d\u005f\u003d\u002b\t\n\\\u0000\u005b\u005d\u007b\u007d\u003b\u0027\u0023\u003a\u0040\u007e\u002c\u002e\u002f\u003c\u003e\u003f\\u007c\u005f\u0078\u0054\u0036\u0035\u0041\u005f";
        String NUMERIC_LOCAL_NAME = "12Woof12";

        NodeRef childNameEscapingNodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
        QName childNameEscapingQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, COMPLEX_LOCAL_NAME);
        QName pathChildNameEscapingQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, ISO9075.encode(COMPLEX_LOCAL_NAME));
        ChildAssociationRef complexCAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, childNameEscapingQName, childNameEscapingNodeRef, true, 0);
        addNode(core, dataModel, 1, 17, 1, testSuperType, null, null, null, "system", new ChildAssociationRef[] { complexCAR }, new NodeRef[] { rootNodeRef }, new String[] { "/"
                + pathChildNameEscapingQName.toString() }, childNameEscapingNodeRef);

        NodeRef numericNameEscapingNodeRef = new NodeRef(new StoreRef("workspace", "SpacesStore"), GUID.generate());
        QName numericNameEscapingQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, NUMERIC_LOCAL_NAME);
        QName pathNumericNameEscapingQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, ISO9075.encode(NUMERIC_LOCAL_NAME));
        ChildAssociationRef numericCAR = new ChildAssociationRef(ContentModel.ASSOC_CHILDREN, rootNodeRef, numericNameEscapingQName, numericNameEscapingNodeRef, true, 0);
        addNode(core, dataModel, 1, 18, 1, testSuperType, null, null, null, "system", new ChildAssociationRef[] { numericCAR }, new NodeRef[] { rootNodeRef }, new String[] { "/"
                + pathNumericNameEscapingQName.toString() }, numericNameEscapingNodeRef);

        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("TestChildNameEscaping", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:" + ISO9075.encode(COMPLEX_LOCAL_NAME) + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:" + ISO9075.encode(NUMERIC_LOCAL_NAME) + "\"", 1);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    public Map<QName, PropertyValue> getOrderProperties()
    {
        double orderDoubleCount = -0.11d + orderTextCount * ((orderTextCount % 2 == 0) ? 0.1d : -0.1d);
        float orderFloatCount = -3.5556f + orderTextCount * ((orderTextCount % 2 == 0) ? 0.82f : -0.82f);
        long orderLongCount = -1999999999999999l + orderTextCount * ((orderTextCount % 2 == 0) ? 299999999999999l : -299999999999999l);
        int orderIntCount = -45764576 + orderTextCount * ((orderTextCount % 2 == 0) ? 8576457 : -8576457);

        Map<QName, PropertyValue> testProperties = new HashMap<QName, PropertyValue>();
        testProperties.put(createdDate, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderDate)));
        testProperties.put(createdTime, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderDate)));
        testProperties.put(orderDouble, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderDoubleCount)));
        testProperties.put(orderFloat, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderFloatCount)));
        testProperties.put(orderLong, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderLongCount)));
        testProperties.put(orderInt, new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, orderIntCount)));
        testProperties.put(
                orderText,
                new StringPropertyValue(DefaultTypeConverter.INSTANCE.convert(String.class, new String(new char[] { (char) ('l' + ((orderTextCount % 2 == 0) ? orderTextCount
                        : -orderTextCount)) }) + " cabbage")));

        testProperties.put(ContentModel.PROP_NAME, new StringPropertyValue(orderNames[orderTextCount]));
        testProperties.put(orderLocalisedText, new StringPropertyValue(orderLocalisedNames[orderTextCount]));

        MLTextPropertyValue mlTextPropLocalisedOrder = new MLTextPropertyValue();
        if (orderLocaliseMLText_en[orderTextCount].length() > 0)
        {
            mlTextPropLocalisedOrder.addValue(Locale.ENGLISH, orderLocaliseMLText_en[orderTextCount]);
        }
        if (orderLocaliseMLText_fr[orderTextCount].length() > 0)
        {
            mlTextPropLocalisedOrder.addValue(Locale.FRENCH, orderLocaliseMLText_fr[orderTextCount]);
        }
        if (orderLocaliseMLText_es[orderTextCount].length() > 0)
        {
            mlTextPropLocalisedOrder.addValue(new Locale("es"), orderLocaliseMLText_es[orderTextCount]);
        }
        if (orderLocaliseMLText_de[orderTextCount].length() > 0)
        {
            mlTextPropLocalisedOrder.addValue(Locale.GERMAN, orderLocaliseMLText_de[orderTextCount]);
        }
        testProperties.put(orderLocalisedMLText, mlTextPropLocalisedOrder);

        MLTextPropertyValue mlTextPropVal = new MLTextPropertyValue();
        mlTextPropVal.addValue(Locale.ENGLISH, new String(new char[] { (char) ('l' + ((orderTextCount % 2 == 0) ? orderTextCount : -orderTextCount)) }) + " banana");
        mlTextPropVal.addValue(Locale.FRENCH, new String(new char[] { (char) ('L' + ((orderTextCount % 2 == 0) ? -orderTextCount : orderTextCount)) }) + " banane");
        mlTextPropVal.addValue(Locale.CHINESE, new String(new char[] { (char) ('香' + ((orderTextCount % 2 == 0) ? orderTextCount : -orderTextCount)) }) + " 香蕉");
        testProperties.put(orderMLText, mlTextPropVal);

        orderDate = Duration.subtract(orderDate, new Duration("P1D"));
        orderTextCount++;
        return testProperties;
    }

    /**
     * @param rsp
     * @return
     * @throws IOException
     * @throws org.apache.lucene.queryParser.ParseException
     */
    private void checkRootNode(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("RootNode", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/.\"", 1);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkQNames(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("QNames", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "QNAME:\"nine\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PRIMARYASSOCTYPEQNAME:\"cm:contains\"", 11);
            testQuery(dataModel, report, solrIndexSearcher, "PRIMARYASSOCTYPEQNAME:\"sys:children\"", 4);
            testQuery(dataModel, report, solrIndexSearcher, "ASSOCTYPEQNAME:\"cm:contains\"", 11);
            testQuery(dataModel, report, solrIndexSearcher, "ASSOCTYPEQNAME:\"sys:children\"", 5);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkType(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Type", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + testType.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + testType.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTTYPE:\"" + testType.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTTYPE:\"" + testType.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + testSuperType.toString() + "\"", 13);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + testSuperType.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 13);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + ContentModel.TYPE_CONTENT.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"cm:content\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"cm:CONTENT\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"CM:CONTENT\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"CONTENT\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"content\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + ContentModel.TYPE_THUMBNAIL.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TYPE:\"" + ContentModel.TYPE_THUMBNAIL.toString() + "\" TYPE:\"" + ContentModel.TYPE_CONTENT.toString() + "\"", 2);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTTYPE:\"" + testSuperType.toString() + "\"", 12);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTTYPE:\"" + testSuperType.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 12);
            testQuery(dataModel, report, solrIndexSearcher, "ASPECT:\"" + testAspect.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "ASPECT:\"" + testAspect.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTASPECT:\"" + testAspect.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "EXACTASPECT:\"" + testAspect.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 1);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkText(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Text", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:fox AND TYPE:\"" + ContentModel.PROP_CONTENT.toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:fox cm\\:name:fox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:fo AND TYPE:\"" + ContentModel.PROP_CONTENT.toString() + "\"", 0);

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"the\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"and\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"over the lazy\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"over a lazy\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":*a*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":*A*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":\"*a*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":\"*A*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":*s*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":*S*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":\"*s*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@"
                    + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "text-indexed-stored-tokenised-atomic").toString()) + ":\"*S*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*A*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*a*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*A*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*a*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*Z*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*z*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*Z*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*z*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:laz*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:laz~", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:la?y", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:?a?y", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*azy", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*az*", 1);

            // Accents

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"\u00E0\u00EA\u00EE\u00F0\u00F1\u00F6\u00FB\u00FF\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"aeidnouy\"", 1);

            // FTS

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"fox\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ":\"fox\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ".mimetype:\"text/plain\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ".locale:\"en_GB\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ".locale:en_*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ".locale:e*_GB", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ".size:\"298\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"fox\"", 0, null, new String[] { "@" + ContentModel.PROP_NAME.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"fox\"", 1, null,
                    new String[] { "@" + ContentModel.PROP_NAME.toString(), "@" + ContentModel.PROP_CONTENT.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"cabbage\"", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"cab*\"", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*bage\"", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*ba*\"", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:cabbage", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*cab*", 15, Locale.ENGLISH, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*bage", 15, null, new String[] { "@" + orderText.toString() }, null);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:dabbage~0.7", 15, null, new String[] { "@" + orderText.toString() }, null);

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfresc?\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfres??\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfre???\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfr????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alf?????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"al??????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"a???????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"????????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"a??re???\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"?lfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"??fresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"???resco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"????esco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"?????sco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"??????co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"???????o\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"???res?o\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"????e?co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"????e?c?\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"???re???\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfresc*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfres*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfre*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfr*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alf*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"al*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"a*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"a****\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*lfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*fresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*resco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*esco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*sco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*o\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"****lf**sc***\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*??*lf**sc***\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alfresc*tutorial\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"alf* tut*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"*co *al\"", 1);

        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkAll(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("MLText", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "ALL:\"fox\"", 1, null, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "ALL:\"fox\"", 0, null, null, new String[] { "@" + ContentModel.PROP_NAME.toString() });
            testQuery(dataModel, report, solrIndexSearcher, "ALL:\"fox\"", 1, null, null,
                    new String[] { "@" + ContentModel.PROP_NAME.toString(), "@" + ContentModel.PROP_CONTENT.toString() });
            testQuery(dataModel, report, solrIndexSearcher, "ALL:\"5.6\"", 1, null, null, null);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkDataType(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("MLText", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "d\\:double:\"5.6\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "d\\:content:\"fox\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "d\\:content:\"fox\"", 1, Locale.US, null, null);

        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkNullAndUnset(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("ISNULL/ISUNSET/ISNOTNULL", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "ISUNSET:\"" + QName.createQName(TEST_NAMESPACE, "null").toString() + "\"", 1);
            // testQuery(dataModel, report, solrIndexSearcher, "ISNULL:\"" + QName.createQName(TEST_NAMESPACE,
            // "null").toString() + "\"", 34);
            testQuery(dataModel, report, solrIndexSearcher, "ISUNSET:\"" + QName.createQName(TEST_NAMESPACE, "path-ista").toString() + "\"", 0);
            // testQuery(dataModel, report, solrIndexSearcher, "ISNULL:\"" + QName.createQName(TEST_NAMESPACE,
            // "path-ista").toString() + "\"", 33);
            testQuery(dataModel, report, solrIndexSearcher, "ISNOTNULL:\"" + QName.createQName(TEST_NAMESPACE, "null").toString() + "\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "ISNOTNULL:\"" + QName.createQName(TEST_NAMESPACE, "path-ista").toString() + "\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "ISUNSET:\"" + QName.createQName(TEST_NAMESPACE, "aspectProperty").toString() + "\"", 1);
            // testQuery(dataModel, report, solrIndexSearcher, "ISNULL:\"" + QName.createQName(TEST_NAMESPACE,
            // "aspectProperty").toString() + "\"", 34);
            testQuery(dataModel, report, solrIndexSearcher, "ISNOTNULL:\"" + QName.createQName(TEST_NAMESPACE, "aspectProperty").toString() + "\"", 0);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkNonField(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("NonField", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:fox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:fo*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:f*x", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:*ox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ":fox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ":fo*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ":f*x", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toString()) + ":*ox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toPrefixString(dataModel.getNamespaceDAO())) + ":fox", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toPrefixString(dataModel.getNamespaceDAO())) + ":fo*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toPrefixString(dataModel.getNamespaceDAO())) + ":f*x", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_CONTENT.toPrefixString(dataModel.getNamespaceDAO())) + ":*ox", 1);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkRanges(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Ranges", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + LuceneQueryParser.escape(orderText.toString()) + ":[a TO b]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + LuceneQueryParser.escape(orderText.toString()) + ":[a TO \uFFFF]", 15);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + LuceneQueryParser.escape(orderText.toString()) + ":[\u0000 TO b]", 2);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + LuceneQueryParser.escape(orderText.toString()) + ":[d TO \uFFFF]", 12);

        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkInternalFields(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel, String nodeRef) throws IOException,
            org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Internal", report);

        for (int i = 1; i < 16; i++)
        {
            testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":LEAF-" + i, 1, null, null, null, null, null, null);
            testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":AUX-" + i, 1, null, null, null, null, null, null);
        }
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":LEAF-*", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":AUX-*", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":ACL-*", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":ACLTX-*", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":TX-*", 1, null, null, null, null, null, null);

        // LID is used internally via ID if a node ref is provided
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ID + ":\"" + nodeRef + "\"", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PARENT + ":\"" + nodeRef + "\"", 4, null, null, null, null, null, null);

        // AbstractLuceneQueryParser.FIELD_LINKASPECT is not used for SOLR

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ANCESTOR + ":\"" + nodeRef + "\"", 10, null, null, null, null, null, null);

        // AbstractLuceneQueryParser.FIELD_ISCONTAINER is not used for SOLR
        // AbstractLuceneQueryParser.FIELD_ISCATEGORY is not used for SOLR

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:one\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:two\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:three\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:four\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:five\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:six\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:seven\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:eight-0\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:eight-1\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:eight-2\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:nine\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:ten\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:eleven\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:twelve\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:thirteen\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:fourteen\"", 2, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:fifteen\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:common\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_QNAME + ":\"cm:link\"", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:one\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:two\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:three\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:four\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:five\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:six\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:seven\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:eight-0\"", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:eight-1\"", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:eight-2\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:nine\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:ten\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:eleven\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:twelve\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:thirteen\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:fourteen\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:fifteen\"", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:common\"", 0, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME + ":\"cm:link\"", 0, null, null, null, null, null, null);

        // AbstractLuceneQueryParser.FIELD_ISROOT is not used in SOLR

        testQueryByHandler(report, core, "/afts",
                AbstractLuceneQueryParser.FIELD_PRIMARYASSOCTYPEQNAME + ":\"" + ContentModel.ASSOC_CHILDREN.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 4, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ISNODE + ":T", 16, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ASSOCTYPEQNAME
                + ":\"" + ContentModel.ASSOC_CHILDREN.toPrefixString(dataModel.getNamespaceDAO()) + "\"", 5, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PRIMARYPARENT + ":\"" + nodeRef + "\"", 2, null, null, null, null, null, null);

        // TYPE and ASPECT is covered in other tests

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_FTSSTATUS + ":\"Clean\"", 16, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":1", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":2", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":3", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":4", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":5", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":6", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":7", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":8", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":9", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":10", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":11", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":12", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":13", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":14", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":15", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":16", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID + ":17", 0, null, null, null, null, null, null);
        // testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID+":*", 16, null, null, null);
        // testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_DBID+":[3 TO 4]", 2, null, null,
        // null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_TXID + ":1", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_INTXID + ":1", 33, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ACLTXID + ":1", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_INACLTXID + ":1", 2, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_INACLTXID + ":2", 0, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_TXCOMMITTIME + ":*", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ACLTXCOMMITTIME + ":*", 1, null, null, null, null, null, null);

        // AbstractLuceneQueryParser.FIELD_EXCEPTION_MESSAGE
        // addNonDictionaryField(AbstractLuceneQueryParser.FIELD_EXCEPTION_STACK

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_ACLID + ":1", 17, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_READER + ":\"GROUP_EVERYONE\"", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":andy", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":bob", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":cid", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":dave", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":eoin", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":fred", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":gail", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":hal", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":ian", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":jake", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":kara", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":loon", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":mike", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":noodle", 1, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_OWNER + ":ood", 1, null, null, null, null, null, null);

        testQueryByHandler(report, core, "/afts", AbstractLuceneQueryParser.FIELD_PARENT_ASSOC_CRC + ":0", 16, null, null, null, null, null, null);
    }

    private void checkAuthorityFilter(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Read Access", report);

        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, null, null, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:andy");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:bob");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:cid");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:dave");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:eoin");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:fred");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:gail");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:hal");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:ian");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:jake");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:kara");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:loon");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:mike");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:noodle");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 1, null, null, null, null, null, "{!afts}|AUTHORITY:ood");
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, null, null, null, null, null, "{!afts}|AUTHORITY:GROUP_EVERYONE");
        
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 3, null, null, null, null, null, "{!afts}|AUTHORITY:andy |AUTHORITY:bob |AUTHORITY:cid");
    }
    
    private void checkPaging(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Read Access", report);

        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 ,15, 16}, null, null, null, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 ,15, 16}, null, 20, 0, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[]{1, 2, 3, 4, 5, 6}, null, 6, 0, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[]{7, 8, 9, 10, 11, 12}, null, 6, 6, null);
        testQueryByHandler(report, core, "/afts", "PATH:\"//.\"", 16, "DBID asc", new int[]{13, 14 ,15, 16}, null, 6, 12, null);
    }

    private void checkMLText(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("MLText", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfresc?\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfres??\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfre???\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfr????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alf?????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"al??????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"a???????\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"????????\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"a??re???\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"?lfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"??fresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"???resco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"????esco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"?????sco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"??????co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"???????o\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"???resco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"???res?o\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"????e?co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"????e?c?\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"???re???\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfresc*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfres*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfre*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alfr*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"alf*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"al*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"a*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"a*****\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*lfresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*fresco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*resco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*esco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*sco\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*co\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*o\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"****lf**sc***\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*??*lf**sc***\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Alfresc*tutorial\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Alf* tut*\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"*co *al\"", 1);

            QName mlQName = QName.createQName(TEST_NAMESPACE, "ml");
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":and", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":\"and\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banana", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banana", 1, Locale.UK, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banana", 1, Locale.ENGLISH, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banane", 1, Locale.FRENCH, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":香蕉", 1, Locale.CHINESE, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banaan", 1, new Locale("nl"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banane", 1, Locale.GERMAN, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":μπανάνα", 1, new Locale("el"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banana", 1, Locale.ITALIAN, null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":バナナ", 1, new Locale("ja"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":바나나", 1, new Locale("ko"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":banana", 1, new Locale("pt"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":банан", 1, new Locale("ru"), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(mlQName.toString()) + ":plátano", 1, new Locale("es"), null, null);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private void checkPropertyTypes(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel, Date testDate, String n01NodeRef) throws IOException,
            org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("PropertyTypes", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            QName qname = QName.createQName(TEST_NAMESPACE, "int-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"1\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":1", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"01\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":01", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"001\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"0001\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[A TO 2]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[0 TO 2]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[0 TO A]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 1}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{0 TO 1}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{0 TO A}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 2}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{1 TO 2}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{1 TO A}", 0);

            qname = QName.createQName(TEST_NAMESPACE, "long-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"2\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"02\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"002\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"0002\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[A TO 2]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[0 TO 2]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[0 TO A]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 2}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{0 TO 2}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{0 TO A}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 3}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{2 TO 3}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{2 TO A}", 0);

            qname = QName.createQName(TEST_NAMESPACE, "float-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"3.4\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[A TO 4]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[3 TO 4]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[3 TO A]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[A TO 3.4]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[3.3 TO 3.4]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[3.3 TO A]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 3.4}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{3.3 TO 3.4}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{3.3 TO A}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"3.40\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"03.4\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"03.40\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "double-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"5.6\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"05.6\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"5.60\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"05.60\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[A TO 5.7]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[5.5 TO 5.7]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":[5.5 TO A]", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 5.6}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{5.5 TO 5.6}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{5.5 TO A}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{A TO 5.7}", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{5.6 TO 5.7}", 0);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":{5.6 TO A}", 0);

            Date date = new Date();
            for (SimpleDateFormatAndResolution df : CachingDateFormat.getLenientFormatters())
            {
                if (df.getResolution() < Calendar.DAY_OF_MONTH)
                {
                    continue;
                }

                String sDate = df.getSimpleDateFormat().format(testDate);

                if (sDate.length() >= 9)
                {
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "date-ista").toString()) + ":\"" + sDate + "\"", 1);
                }
                testQuery(dataModel, report, solrIndexSearcher, "\\@"
                        + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":\"" + sDate + "\"", 1);

                sDate = df.getSimpleDateFormat().format(date);
                testQuery(dataModel, report, solrIndexSearcher, "\\@cm\\:CrEaTeD:[MIN TO " + sDate + "]", 1);
                testQuery(dataModel, report, solrIndexSearcher, "\\@cm\\:created:[MIN TO NOW]", 1);
                testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(ContentModel.PROP_CREATED.toString()) + ":[MIN TO " + sDate + "]", 1);

                if (sDate.length() >= 9)
                {
                    sDate = df.getSimpleDateFormat().format(testDate);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "date-ista").toString()) + ":[" + sDate + " TO " + sDate + "]", 1);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "date-ista").toString()) + ":[MIN  TO " + sDate + "]", 1);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "date-ista").toString()) + ":[" + sDate + " TO MAX]", 1);
                }

                sDate = CachingDateFormat.getDateFormat().format(testDate);
                testQuery(dataModel, report, solrIndexSearcher, "\\@"
                        + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":[MIN TO " + sDate + "]", 1);

                sDate = df.getSimpleDateFormat().format(testDate);
                for (long i : new long[] { 333, 20000, 20 * 60 * 1000, 8 * 60 * 60 * 1000, 10 * 24 * 60 * 60 * 1000, 4 * 30 * 24 * 60 * 60 * 1000,
                        10 * 12 * 30 * 24 * 60 * 60 * 1000 })
                {
                    String startDate = df.getSimpleDateFormat().format(new Date(testDate.getTime() - i));
                    String endDate = df.getSimpleDateFormat().format(new Date(testDate.getTime() + i));

                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":[" + startDate + " TO " + endDate + "]", 1);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":[" + sDate + " TO " + endDate + "]", 1);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":[" + startDate + " TO " + sDate + "]", 1);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":{" + sDate + " TO " + endDate + "}", 0);
                    testQuery(dataModel, report, solrIndexSearcher, "\\@"
                            + SolrQueryParser.escape(QName.createQName(TEST_NAMESPACE, "datetime-ista").toString()) + ":{" + startDate + " TO " + sDate + "}", 0);

                }
            }

            qname = QName.createQName(TEST_NAMESPACE, "boolean-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"true\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "qname-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"{wibble}wobble\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "category-ista");
            testQuery(
                    dataModel,
                    report,
                    solrIndexSearcher,
                    "\\@"
                            + SolrQueryParser.escape(qname.toString()) + ":\""
                            + DefaultTypeConverter.INSTANCE.convert(String.class, new NodeRef(new StoreRef("proto", "id"), "CategoryId")) + "\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "noderef-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"" + n01NodeRef + "\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "path-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"/{" + NamespaceService.CONTENT_MODEL_1_0_URI + "}three\"", 1);

            qname = QName.createQName(TEST_NAMESPACE, "any-many-ista");
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"100\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "\\@" + SolrQueryParser.escape(qname.toString()) + ":\"anyValueAsString\"", 1);

            //

            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"Tutorial Alfresco\"~0", 0);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"Tutorial Alfresco\"~1", 0);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"Tutorial Alfresco\"~2", 1);
            testQuery(dataModel, report, solrIndexSearcher, "TEXT:\"Tutorial Alfresco\"~3", 1);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Alfresco Tutorial\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Tutorial Alfresco\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Tutorial Alfresco\"~0", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Tutorial Alfresco\"~1", 0);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Tutorial Alfresco\"~2", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(ContentModel.PROP_DESCRIPTION.toString()) + ":\"Tutorial Alfresco\"~3", 1);

            qname = QName.createQName(TEST_NAMESPACE, "mltext-many-ista");
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":лемур", 1, (new Locale("ru")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":lemur", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":chou", 1, (new Locale("fr")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":cabbage", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":cabba*", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":ca*ge", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":*bage", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":cabage~", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":*b?ag?", 1, (new Locale("en")), null, null);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":cho*", 1, (new Locale("fr")), null, null);

            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(QName.createQName(TEST_NAMESPACE, "content-many-ista").toString()) + ":multicontent", 1);

            qname = QName.createQName(TEST_NAMESPACE, "locale-ista");
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":\"en_GB_\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":en_GB_", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":en_*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":*_GB_*", 1);
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":*_gb_*", 1);

            qname = QName.createQName(TEST_NAMESPACE, "period-ista");
            testQuery(dataModel, report, solrIndexSearcher, "@" + LuceneQueryParser.escape(qname.toString()) + ":\"period|12\"", 1);

        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
    }

    private String checkPaths(SolrQueryResponse rsp, SolrCore core, AlfrescoSolrDataModel dataModel) throws IOException, org.apache.lucene.queryParser.ParseException
    {
        NamedList<Object> report = new SimpleOrderedMap<Object>();
        rsp.add("Paths", report);
        RefCounted<SolrIndexSearcher> refCounted = null;
        try
        {
            refCounted = core.getSearcher(false, true, null);
            SolrIndexSearcher solrIndexSearcher = refCounted.get();

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:three\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:four\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:eight-0\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:five\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:one\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:two\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:one\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:two\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:six\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:seven\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:eight-1\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:eight-2\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:eight-2\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:eight-1\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:two/cm:eight-0\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:eight-0\"", 0);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:nine\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:ten\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:eleven\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:twelve\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:twelve/cm:thirteen\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:twelve/cm:thirteen/cm:fourteen\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:twelve/cm:thirteen/cm:common\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:five/cm:twelve/cm:common\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:*\"", 5);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:*/cm:*\"", 6);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:*/cm:five\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:*/cm:*/cm:*\"", 6);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:*\"", 4);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:*/cm:five/cm:*\"", 5);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/cm:*/cm:nine\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/*\"", 5);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/*/*\"", 6);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/*/cm:five\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/*/*/*\"", 6);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/*\"", 4);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/*/cm:five/*\"", 5);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/cm:one/*/cm:nine\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//.\"", 16);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//*\"", 15);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//*/.\"", 15);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//*/./.\"", 15);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//./*\"", 15);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//././*/././.\"", 15);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"//cm:common\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//common\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one/five//*\"", 7);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one/five//.\"", 8);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//five/nine\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//thirteen/fourteen\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//thirteen/fourteen/.\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//thirteen/fourteen//.\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one//thirteen/fourteen//.//.\"", 1);

            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/one\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/two\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/three\"", 1);
            testQuery(dataModel, report, solrIndexSearcher, "PATH:\"/four\"", 1);
        }
        finally
        {
            if (refCounted != null)
            {
                refCounted.decref();
            }
        }
        return "PASSED";
    }

    /**
     * @param dataModel
     * @param report
     * @param solrIndexSearcher
     * @throws ParseException
     * @throws IOException
     */
    private void testQuery(AlfrescoSolrDataModel dataModel, NamedList<Object> report, SolrIndexSearcher solrIndexSearcher, String queryString, int count, Locale locale,
            String[] textAttributes, String[] allAttributes) throws org.apache.lucene.queryParser.ParseException, IOException
    {
        SearchParameters searchParameters = new SearchParameters();
        searchParameters.setQuery(queryString);
        if (locale != null)
        {
            searchParameters.addLocale(locale);
        }
        if (textAttributes != null)
        {
            for (String textAttribute : textAttributes)
            {
                searchParameters.addTextAttribute(textAttribute);
            }
        }
        if (allAttributes != null)
        {
            for (String allAttribute : allAttributes)
            {
                searchParameters.addAllAttribute(allAttribute);
            }
        }
        // Query query = dataModel.getFTSQuery(searchParameters, solrIndexSearcher.getIndexReader());
        Query query = dataModel.getLuceneQueryParser(searchParameters, solrIndexSearcher.getIndexReader()).parse(queryString);
        TopDocs docs = solrIndexSearcher.search(query, count * 2 + 10);
        if (docs.totalHits != count)
        {
            report.add("FAILED: " + fixQueryString(queryString), docs.totalHits);
        }
        else
        {
            report.add("Passed: " + fixQueryString(queryString), docs.totalHits);
        }
    }

    private String fixQueryString(String queryString)
    {
        return queryString.replace("\uFFFF", "<Unicode FFFF>");
    }

    private void testQuery(AlfrescoSolrDataModel dataModel, NamedList<Object> report, SolrIndexSearcher solrIndexSearcher, String queryString, int count)
            throws org.apache.lucene.queryParser.ParseException, IOException
    {
        testQuery(dataModel, report, solrIndexSearcher, queryString, count, null, null, null);
    }

    private NodeRef addNode(SolrCore core, AlfrescoSolrDataModel dataModel, int txid, int dbid, int aclid, QName type, QName[] aspects, Map<QName, PropertyValue> properties,
            Map<QName, String> content, String owner, ChildAssociationRef[] parentAssocs, NodeRef[] ancestors, String[] paths, NodeRef nodeRef) throws IOException
    {
        AddUpdateCommand leafDocCmd = new AddUpdateCommand();
        leafDocCmd.overwriteCommitted = true;
        leafDocCmd.overwritePending = true;
        leafDocCmd.solrDoc = createLeafDocument(dataModel, txid, dbid, nodeRef, type, aspects, properties, content);
        leafDocCmd.doc = CoreTracker.toDocument(leafDocCmd.getSolrInputDocument(), core.getSchema(), dataModel);

        AddUpdateCommand auxDocCmd = new AddUpdateCommand();
        auxDocCmd.overwriteCommitted = true;
        auxDocCmd.overwritePending = true;
        auxDocCmd.solrDoc = createAuxDocument(txid, dbid, aclid, paths, owner, parentAssocs, ancestors);
        auxDocCmd.doc = CoreTracker.toDocument(auxDocCmd.getSolrInputDocument(), core.getSchema(), dataModel);

        if (leafDocCmd.doc != null)
        {
            core.getUpdateHandler().addDoc(leafDocCmd);
        }
        if (auxDocCmd.doc != null)
        {
            core.getUpdateHandler().addDoc(auxDocCmd);
        }

        core.getUpdateHandler().commit(new CommitUpdateCommand(false));

        return nodeRef;
    }

    /**
     * @param i
     * @param j
     * @throws IOException
     */
    private void addStoreRoot(SolrCore core, AlfrescoSolrDataModel dataModel, NodeRef rootNodeRef, int txid, int dbid, int acltxid, int aclid) throws IOException
    {
        AddUpdateCommand leafDocCmd = new AddUpdateCommand();
        leafDocCmd.overwriteCommitted = true;
        leafDocCmd.overwritePending = true;
        leafDocCmd.solrDoc = createLeafDocument(dataModel, txid, dbid, rootNodeRef, ContentModel.TYPE_STOREROOT, new QName[] { ContentModel.ASPECT_ROOT }, null, null);
        leafDocCmd.doc = CoreTracker.toDocument(leafDocCmd.getSolrInputDocument(), core.getSchema(), dataModel);

        AddUpdateCommand auxDocCmd = new AddUpdateCommand();
        auxDocCmd.overwriteCommitted = true;
        auxDocCmd.overwritePending = true;
        auxDocCmd.solrDoc = createAuxDocument(txid, dbid, aclid, new String[] { "/" }, "system", null, null);
        auxDocCmd.doc = CoreTracker.toDocument(auxDocCmd.getSolrInputDocument(), core.getSchema(), dataModel);

        if (leafDocCmd.doc != null)
        {
            core.getUpdateHandler().addDoc(leafDocCmd);
        }
        if (auxDocCmd.doc != null)
        {
            core.getUpdateHandler().addDoc(auxDocCmd);
        }

        AddUpdateCommand aclTxCmd = new AddUpdateCommand();
        aclTxCmd.overwriteCommitted = true;
        aclTxCmd.overwritePending = true;
        SolrInputDocument aclTxSol = new SolrInputDocument();
        aclTxSol.addField(AbstractLuceneQueryParser.FIELD_ID, "ACLTX-" + acltxid);
        aclTxSol.addField(AbstractLuceneQueryParser.FIELD_ACLTXID, acltxid);
        aclTxSol.addField(AbstractLuceneQueryParser.FIELD_INACLTXID, acltxid);
        aclTxSol.addField(AbstractLuceneQueryParser.FIELD_ACLTXCOMMITTIME, (new Date()).getTime());
        aclTxCmd.solrDoc = aclTxSol;
        aclTxCmd.doc = CoreTracker.toDocument(aclTxCmd.getSolrInputDocument(), core.getSchema(), dataModel);
        core.getUpdateHandler().addDoc(aclTxCmd);

        AddUpdateCommand aclCmd = new AddUpdateCommand();
        aclCmd.overwriteCommitted = true;
        aclCmd.overwritePending = true;
        SolrInputDocument aclSol = new SolrInputDocument();
        aclSol.addField(AbstractLuceneQueryParser.FIELD_ID, "ACL-" + aclid);
        aclSol.addField(AbstractLuceneQueryParser.FIELD_ACLID, aclid);
        aclSol.addField(AbstractLuceneQueryParser.FIELD_INACLTXID, "" + acltxid);
        aclSol.addField(AbstractLuceneQueryParser.FIELD_READER, "GROUP_EVERYONE");
        aclSol.addField(AbstractLuceneQueryParser.FIELD_READER, "pig");
        aclCmd.solrDoc = aclSol;
        aclCmd.doc = CoreTracker.toDocument(aclCmd.getSolrInputDocument(), core.getSchema(), dataModel);
        core.getUpdateHandler().addDoc(aclCmd);

        AddUpdateCommand txCmd = new AddUpdateCommand();
        txCmd.overwriteCommitted = true;
        txCmd.overwritePending = true;
        SolrInputDocument input = new SolrInputDocument();
        input.addField(AbstractLuceneQueryParser.FIELD_ID, "TX-" + txid);
        input.addField(AbstractLuceneQueryParser.FIELD_TXID, txid);
        input.addField(AbstractLuceneQueryParser.FIELD_INTXID, txid);
        input.addField(AbstractLuceneQueryParser.FIELD_TXCOMMITTIME, (new Date()).getTime());
        txCmd.solrDoc = input;
        txCmd.doc = CoreTracker.toDocument(txCmd.getSolrInputDocument(), core.getSchema(), dataModel);
        core.getUpdateHandler().addDoc(txCmd);

        core.getUpdateHandler().commit(new CommitUpdateCommand(false));
    }

    public SolrInputDocument createLeafDocument(AlfrescoSolrDataModel dataModel, int txid, int dbid, NodeRef nodeRef, QName type, QName[] aspects,
            Map<QName, PropertyValue> properties, Map<QName, String> content) throws IOException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(AbstractLuceneQueryParser.FIELD_ID, "LEAF-" + dbid);
        doc.addField(AbstractLuceneQueryParser.FIELD_DBID, "" + dbid);
        doc.addField(AbstractLuceneQueryParser.FIELD_LID, nodeRef);
        doc.addField(AbstractLuceneQueryParser.FIELD_INTXID, "" + txid);

        if (properties != null)
        {
            for (QName propertyQname : properties.keySet())
            {
                if (dataModel.isIndexedOrStored(propertyQname))
                {
                    PropertyValue value = properties.get(propertyQname);
                    if (value != null)
                    {
                        if (value instanceof ContentPropertyValue)
                        {
                            addContentPropertyToDoc(doc, propertyQname, (ContentPropertyValue) value, content);

                        }
                        else if (value instanceof MLTextPropertyValue)
                        {
                            addMLTextPropertyToDoc(dataModel, doc, propertyQname, (MLTextPropertyValue) value);
                        }
                        else if (value instanceof MultiPropertyValue)
                        {
                            MultiPropertyValue typedValue = (MultiPropertyValue) value;
                            for (PropertyValue singleValue : typedValue.getValues())
                            {
                                if (singleValue instanceof ContentPropertyValue)
                                {
                                    addContentPropertyToDoc(doc, propertyQname, (ContentPropertyValue) singleValue, content);
                                }
                                else if (singleValue instanceof MLTextPropertyValue)
                                {
                                    addMLTextPropertyToDoc(dataModel, doc, propertyQname, (MLTextPropertyValue) singleValue);

                                }
                                else if (singleValue instanceof StringPropertyValue)
                                {
                                    addStringPropertyToDoc(dataModel, doc, propertyQname, (StringPropertyValue) singleValue, properties);
                                }
                            }
                        }
                        else if (value instanceof StringPropertyValue)
                        {
                            addStringPropertyToDoc(dataModel, doc, propertyQname, (StringPropertyValue) value, properties);
                        }

                    }
                }
            }
        }

        doc.addField(AbstractLuceneQueryParser.FIELD_TYPE, type);
        if (aspects != null)
        {
            for (QName aspect : aspects)
            {
                doc.addField(AbstractLuceneQueryParser.FIELD_ASPECT, aspect);
            }
        }
        doc.addField(AbstractLuceneQueryParser.FIELD_ISNODE, "T");
        doc.addField(AbstractLuceneQueryParser.FIELD_FTSSTATUS, "Clean");
        doc.addField(AbstractLuceneQueryParser.FIELD_TENANT, "_DEFAULT_");

        return doc;
    }

    private void addStringPropertyToDoc(AlfrescoSolrDataModel dataModel, SolrInputDocument doc, QName propertyQName, StringPropertyValue stringPropertyValue,
            Map<QName, PropertyValue> properties) throws IOException
    {
        PropertyDefinition propertyDefinition = dataModel.getPropertyDefinition(propertyQName);
        if (propertyDefinition != null)
        {
            if (propertyDefinition.getDataType().getName().equals(DataTypeDefinition.DATETIME))
            {
                doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), stringPropertyValue.getValue());
                doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".sort", stringPropertyValue.getValue());
            }
            else if (propertyDefinition.getDataType().getName().equals(DataTypeDefinition.TEXT))
            {
                Locale locale = null;

                PropertyValue localePropertyValue = properties.get(ContentModel.PROP_LOCALE);
                if (localePropertyValue != null)
                {
                    locale = DefaultTypeConverter.INSTANCE.convert(Locale.class, ((StringPropertyValue) localePropertyValue).getValue());
                }

                if (locale == null)
                {
                    locale = I18NUtil.getLocale();
                }

                StringBuilder builder;
                builder = new StringBuilder();
                builder.append("\u0000").append(locale.toString()).append("\u0000").append(stringPropertyValue.getValue());
                if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.TRUE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
                {
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), builder.toString());
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".__", builder.toString());
                }
                if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.FALSE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
                {
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".u", builder.toString());
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".__.u", builder.toString());
                }

                if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.FALSE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
                {
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".sort", builder.toString());
                }

            }
            else
            {
                doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), stringPropertyValue.getValue());
            }

        }
        else
        {
            doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), stringPropertyValue.getValue());
        }
    }

    private void addContentPropertyToDoc(SolrInputDocument doc, QName propertyQName, ContentPropertyValue contentPropertyValue, Map<QName, String> content) throws IOException
    {
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".size", contentPropertyValue.getLength());
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".locale", contentPropertyValue.getLocale());
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".mimetype", contentPropertyValue.getMimetype());
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".encoding", contentPropertyValue.getEncoding());

        // doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() +
        // ".transformationStatus", response.getStatus());
        // doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() +
        // ".transformationTime", response.getTransformDuration());
        // doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() +
        // ".transformationException", response.getTransformException());

        String value = "";
        if (content != null)
        {
            value = content.get(propertyQName);
            if (value == null)
            {
                value = "";
            }
        }
        StringReader isr = new StringReader(value);
        StringBuilder builder = new StringBuilder();
        builder.append("\u0000").append(contentPropertyValue.getLocale().toString()).append("\u0000");
        StringReader prefix = new StringReader(builder.toString());
        Reader multiReader = new MultiReader(prefix, isr);
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), multiReader);

        isr = new StringReader(value);
        builder = new StringBuilder();
        builder.append("\u0000").append(contentPropertyValue.getLocale().toString()).append("\u0000");
        prefix = new StringReader(builder.toString());
        multiReader = new MultiReader(prefix, isr);
        doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".__", multiReader);

    }

    private void addMLTextPropertyToDoc(AlfrescoSolrDataModel dataModel, SolrInputDocument doc, QName propertyQName, MLTextPropertyValue mlTextPropertyValue) throws IOException
    {
        PropertyDefinition propertyDefinition = dataModel.getPropertyDefinition(propertyQName);
        if (propertyDefinition != null)
        {
            StringBuilder sort = new StringBuilder();
            for (Locale locale : mlTextPropertyValue.getLocales())
            {
                StringBuilder builder = new StringBuilder();
                builder.append("\u0000").append(locale.toString()).append("\u0000").append(mlTextPropertyValue.getValue(locale));

                if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.TRUE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
                {
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), builder.toString());
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".__", builder.toString());
                }
                if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.FALSE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
                {
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".u", builder.toString());
                    doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".__.u", builder.toString());
                }

                if (sort.length() > 0)
                {
                    sort.append("\u0000");
                }
                sort.append(builder.toString());
            }

            if ((propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.FALSE) || (propertyDefinition.getIndexTokenisationMode() == IndexTokenisationMode.BOTH))
            {
                doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString() + ".sort", sort.toString());
            }
        }
        else
        {
            for (Locale locale : mlTextPropertyValue.getLocales())
            {
                doc.addField(AbstractLuceneQueryParser.PROPERTY_FIELD_PREFIX + propertyQName.toString(), mlTextPropertyValue.getValue(locale));
            }
        }

    }

    private SolrInputDocument createAuxDocument(int txid, int dbid, int aclid, String[] paths, String owner, ChildAssociationRef[] parentAssocs, NodeRef[] ancestors)
    {
        SolrInputDocument aux = new SolrInputDocument();
        aux.addField(AbstractLuceneQueryParser.FIELD_ID, "AUX-" + dbid);
        aux.addField(AbstractLuceneQueryParser.FIELD_DBID, "" + dbid);
        aux.addField(AbstractLuceneQueryParser.FIELD_ACLID, "" + aclid);
        aux.addField(AbstractLuceneQueryParser.FIELD_INTXID, "" + txid);

        if (paths != null)
        {
            for (String path : paths)
            {
                aux.addField(AbstractLuceneQueryParser.FIELD_PATH, path);
            }
        }

        if (owner != null)
        {
            aux.addField(AbstractLuceneQueryParser.FIELD_OWNER, owner);
        }
        aux.addField(AbstractLuceneQueryParser.FIELD_PARENT_ASSOC_CRC, "0");

        StringBuilder qNameBuffer = new StringBuilder(64);
        StringBuilder assocTypeQNameBuffer = new StringBuilder(64);
        if (parentAssocs != null)
        {
            for (ChildAssociationRef childAssocRef : parentAssocs)
            {
                if (qNameBuffer.length() > 0)
                {
                    qNameBuffer.append(";/");
                    assocTypeQNameBuffer.append(";/");
                }
                qNameBuffer.append(ISO9075.getXPathName(childAssocRef.getQName()));
                assocTypeQNameBuffer.append(ISO9075.getXPathName(childAssocRef.getTypeQName()));
                aux.addField(AbstractLuceneQueryParser.FIELD_PARENT, childAssocRef.getParentRef());

                if (childAssocRef.isPrimary())
                {
                    aux.addField(AbstractLuceneQueryParser.FIELD_PRIMARYPARENT, childAssocRef.getParentRef());
                    aux.addField(AbstractLuceneQueryParser.FIELD_PRIMARYASSOCTYPEQNAME, ISO9075.getXPathName(childAssocRef.getTypeQName()));
                    aux.addField(AbstractLuceneQueryParser.FIELD_PRIMARYASSOCQNAME, ISO9075.getXPathName(childAssocRef.getQName()));

                }
            }
            aux.addField(AbstractLuceneQueryParser.FIELD_ASSOCTYPEQNAME, assocTypeQNameBuffer.toString());
            aux.addField(AbstractLuceneQueryParser.FIELD_QNAME, qNameBuffer.toString());
        }
        if (ancestors != null)
        {
            for (NodeRef ancestor : ancestors)
            {
                aux.addField(AbstractLuceneQueryParser.FIELD_ANCESTOR, ancestor.toString());
            }
        }
        return aux;
    }

    public static SolrInputDocument createRootAclDocument()
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("ACLID", "1");
        doc.addField("READER", "ROLE_ALL");
        doc.addField("READER", "ROLE_JUST_ROOT");
        doc.addField("ID", "ACL-1");
        return doc;
    }

    /**
     * @param cname
     * @param detail
     * @param hist
     * @param values
     * @param tracker
     * @param report
     */
    private void addCoreSummary(String cname, boolean detail, boolean hist, boolean values, CoreTracker tracker, NamedList<Object> report)
    {
        NamedList<Object> coreSummary = new SimpleOrderedMap<Object>();
        long lastIndexTxCommitTime = tracker.getLastIndexedTxCommitTime();
        long lastIndexedTxId = tracker.getLastIndexedTxId();
        long lastTxCommitTimeOnServer = tracker.getLastTxCommitTimeOnServer();
        long lastTxIdOnServer = tracker.getLastTxIdOnServer();
        Date lastIndexTxCommitDate = new Date(lastIndexTxCommitTime);
        Date lastTxOnServerDate = new Date(lastTxCommitTimeOnServer);

        long lastIndexChangeSetCommitTime = tracker.getLastIndexedChangeSetCommitTime();
        long lastIndexedChangeSetId = tracker.getLastIndexedChangeSetId();
        long lastChangeSetCommitTimeOnServer = tracker.getLastChangeSetCommitTimeOnServer();
        long lastChangeSetIdOnServer = tracker.getLastChangeSetIdOnServer();
        Date lastIndexChangeSetCommitDate = new Date(lastIndexChangeSetCommitTime);
        Date lastChangeSetOnServerDate = new Date(lastChangeSetCommitTimeOnServer);

        long remainingTxTimeMillis = (long) ((lastTxIdOnServer - lastIndexedTxId) * tracker.getTrackerStats().getMeanDocsPerTx() * tracker.getTrackerStats().getMeanNodeIndexTime() / tracker
                .getTrackerStats().getNodeIndexingThreadCount());
        Date now = new Date();
        Date end = new Date(now.getTime() + remainingTxTimeMillis);
        Duration remainingTx = new Duration(now, end);

        long remainingChangeSetTimeMillis = (long) ((lastChangeSetIdOnServer - lastIndexedChangeSetId)
                * tracker.getTrackerStats().getMeanAclsPerChangeSet() * tracker.getTrackerStats().getMeanAclIndexTime() / tracker.getTrackerStats().getNodeIndexingThreadCount());
        now = new Date();
        end = new Date(now.getTime() + remainingChangeSetTimeMillis);
        Duration remainingChangeSet = new Duration(now, end);

        Duration txLag = new Duration(lastIndexTxCommitDate, lastTxOnServerDate);
        Duration changeSetLag = new Duration(lastIndexChangeSetCommitDate, lastChangeSetOnServerDate);

        coreSummary.add("Active", tracker.isRunning());

        // TX

        coreSummary.add("Last Index TX Commit Time", lastIndexTxCommitTime);
        coreSummary.add("Last Index TX Commit Date", lastIndexTxCommitDate);
        coreSummary.add("TX Lag", (lastTxCommitTimeOnServer - lastIndexTxCommitTime) / 1000 + " s");
        coreSummary.add("TX Duration", txLag.toString());
        coreSummary.add("Timestamp for last TX on server", lastTxCommitTimeOnServer);
        coreSummary.add("Date for last TX on server", lastTxOnServerDate);
        coreSummary.add("Id for last TX on server", lastTxIdOnServer);
        coreSummary.add("Id for last TX in index", lastIndexedTxId);
        coreSummary.add("Approx transactions remaining", lastTxIdOnServer - lastIndexedTxId);
        coreSummary.add("Approx transaction indexing time remaining", remainingTx.largestComponentformattedString());

        // Change set

        coreSummary.add("Last Index Change Set Commit Time", lastIndexChangeSetCommitTime);
        coreSummary.add("Last Index Change Set Commit Date", lastIndexChangeSetCommitDate);
        coreSummary.add("Change Set Lag", (lastChangeSetCommitTimeOnServer - lastIndexChangeSetCommitTime) / 1000 + " s");
        coreSummary.add("Change Set Duration", changeSetLag.toString());
        coreSummary.add("Timestamp for last Change Set on server", lastChangeSetCommitTimeOnServer);
        coreSummary.add("Date for last Change Set on server", lastChangeSetOnServerDate);
        coreSummary.add("Id for last Change Set on server", lastChangeSetIdOnServer);
        coreSummary.add("Id for last Change Set in index", lastIndexedChangeSetId);
        coreSummary.add("Approx change sets remaining", lastChangeSetIdOnServer - lastIndexedChangeSetId);
        coreSummary.add("Approx change set indexing time remaining", remainingChangeSet.largestComponentformattedString());

        // Stats

        coreSummary.add("Model sync times (ms)", tracker.getTrackerStats().getModelTimes().getNamedList(detail, hist, values));
        coreSummary.add("Acl index time (ms)", tracker.getTrackerStats().getAclTimes().getNamedList(detail, hist, values));
        coreSummary.add("Node index time (ms)", tracker.getTrackerStats().getNodeTimes().getNamedList(detail, hist, values));
        coreSummary.add("Docs/Tx", tracker.getTrackerStats().getTxDocs().getNamedList(detail, hist, values));
        coreSummary.add("Doc Transformation time (ms)", tracker.getTrackerStats().getDocTransformationTimes().getNamedList(detail, hist, values));

        report.add(cname, coreSummary);
    }

    private NamedList<Object> buildAclTxReport(CoreTracker tracker, Long acltxid) throws AuthenticationException, IOException, JSONException
    {
        NamedList<Object> nr = new SimpleOrderedMap<Object>();
        nr.add("TXID", acltxid);
        nr.add("transaction", buildTrackerReport(tracker, 0l, 0l, acltxid, acltxid, null, null));
        NamedList<Object> nodes = new SimpleOrderedMap<Object>();
        // add node reports ....
        List<Long> dbAclIds = tracker.getAclsForDbAclTransaction(acltxid);
        for (Long aclid : dbAclIds)
        {
            nodes.add("ACLID " + aclid, buildAclReport(tracker, aclid));
        }
        nr.add("aclTxDbAclCount", dbAclIds.size());
        nr.add("nodes", nodes);
        return nr;
    }

    private NamedList<Object> buildAclReport(CoreTracker tracker, Long aclid) throws IOException, JSONException
    {
        AclReport aclReport = tracker.checkAcl(aclid);

        NamedList<Object> nr = new SimpleOrderedMap<Object>();
        nr.add("Acl Id", aclReport.getAclId());
        nr.add("Acl doc in index", aclReport.getIndexAclDoc());
        if (aclReport.getIndexAclDoc() != null)
        {
            nr.add("Acl tx in Index", aclReport.getIndexAclTx());
        }

        return nr;
    }

    private NamedList<Object> buildTxReport(CoreTracker tracker, Long txid) throws AuthenticationException, IOException, JSONException
    {
        NamedList<Object> nr = new SimpleOrderedMap<Object>();
        nr.add("TXID", txid);
        nr.add("transaction", buildTrackerReport(tracker, txid, txid, 0l, 0l, null, null));
        NamedList<Object> nodes = new SimpleOrderedMap<Object>();
        // add node reports ....
        List<Node> dbNodes = tracker.getFullNodesForDbTransaction(txid);
        for (Node node : dbNodes)
        {
            nodes.add("DBID " + node.getId(), buildNodeReport(tracker, node));
        }

        nr.add("txDbNodeCount", dbNodes.size());
        nr.add("nodes", nodes);
        return nr;
    }

    private NamedList<Object> buildNodeReport(CoreTracker tracker, Node node) throws IOException, JSONException
    {
        NodeReport nodeReport = tracker.checkNode(node);

        NamedList<Object> nr = new SimpleOrderedMap<Object>();
        nr.add("Node DBID", nodeReport.getDbid());
        nr.add("DB TX", nodeReport.getDbTx());
        nr.add("DB TX status", nodeReport.getDbNodeStatus().toString());
        nr.add("Leaf doc in Index", nodeReport.getIndexLeafDoc());
        nr.add("Aux doc in Index", nodeReport.getIndexAuxDoc());
        if (nodeReport.getIndexLeafDoc() != null)
        {
            nr.add("Leaf tx in Index", nodeReport.getIndexLeafTx());
        }
        if (nodeReport.getIndexAuxDoc() != null)
        {
            nr.add("Aux tx in Index", nodeReport.getIndexAuxTx());
        }
        return nr;
    }

    private NamedList<Object> buildNodeReport(CoreTracker tracker, Long dbid) throws IOException, JSONException
    {
        NodeReport nodeReport = tracker.checkNode(dbid);

        NamedList<Object> nr = new SimpleOrderedMap<Object>();
        nr.add("Node DBID", nodeReport.getDbid());
        nr.add("DB TX", nodeReport.getDbTx());
        nr.add("DB TX status", nodeReport.getDbNodeStatus().toString());
        nr.add("Leaf doc in Index", nodeReport.getIndexLeafDoc());
        nr.add("Aux doc in Index", nodeReport.getIndexAuxDoc());
        if (nodeReport.getIndexLeafDoc() != null)
        {
            nr.add("Leaf tx in Index", nodeReport.getIndexLeafTx());
        }
        if (nodeReport.getIndexAuxDoc() != null)
        {
            nr.add("Aux tx in Index", nodeReport.getIndexAuxTx());
        }
        return nr;
    }

    private NamedList<Object> buildTrackerReport(CoreTracker tracker, Long fromTx, Long toTx, Long fromAclTx, Long toAclTx, Long fromTime, Long toTime) throws IOException,
            JSONException, AuthenticationException
    {
        IndexHealthReport indexHealthReport = tracker.checkIndex(fromTx, toTx, fromAclTx, toAclTx, fromTime, toTime);

        NamedList<Object> ihr = new SimpleOrderedMap<Object>();
        ihr.add("DB transaction count", indexHealthReport.getDbTransactionCount());
        ihr.add("DB acl transaction count", indexHealthReport.getDbAclTransactionCount());
        ihr.add("Count of duplicated transactions in the index", indexHealthReport.getDuplicatedTxInIndex().cardinality());
        if (indexHealthReport.getDuplicatedTxInIndex().cardinality() > 0)
        {
            ihr.add("First duplicate", indexHealthReport.getDuplicatedTxInIndex().nextSetBit(0L));
        }
        ihr.add("Count of duplicated acl transactions in the index", indexHealthReport.getDuplicatedAclTxInIndex().cardinality());
        if (indexHealthReport.getDuplicatedAclTxInIndex().cardinality() > 0)
        {
            ihr.add("First duplicate acl tx", indexHealthReport.getDuplicatedAclTxInIndex().nextSetBit(0L));
        }
        ihr.add("Count of transactions in the index but not the DB", indexHealthReport.getTxInIndexButNotInDb().cardinality());
        if (indexHealthReport.getTxInIndexButNotInDb().cardinality() > 0)
        {
            ihr.add("First transaction in the index but not the DB", indexHealthReport.getTxInIndexButNotInDb().nextSetBit(0L));
        }
        ihr.add("Count of acl transactions in the index but not the DB", indexHealthReport.getAclTxInIndexButNotInDb().cardinality());
        if (indexHealthReport.getAclTxInIndexButNotInDb().cardinality() > 0)
        {
            ihr.add("First acl transaction in the index but not the DB", indexHealthReport.getAclTxInIndexButNotInDb().nextSetBit(0L));
        }
        ihr.add("Count of missing transactions from the Index", indexHealthReport.getMissingTxFromIndex().cardinality());
        if (indexHealthReport.getMissingTxFromIndex().cardinality() > 0)
        {
            ihr.add("First transaction missing from the Index", indexHealthReport.getMissingTxFromIndex().nextSetBit(0L));
        }
        ihr.add("Count of missing acl transactions from the Index", indexHealthReport.getMissingAclTxFromIndex().cardinality());
        if (indexHealthReport.getMissingAclTxFromIndex().cardinality() > 0)
        {
            ihr.add("First acl transaction missing from the Index", indexHealthReport.getMissingAclTxFromIndex().nextSetBit(0L));
        }
        ihr.add("Index transaction count", indexHealthReport.getTransactionDocsInIndex());
        ihr.add("Index acl transaction count", indexHealthReport.getAclTransactionDocsInIndex());
        ihr.add("Index unique transaction count", indexHealthReport.getTransactionDocsInIndex());
        ihr.add("Index unique acl transaction count", indexHealthReport.getAclTransactionDocsInIndex());
        ihr.add("Index leaf count", indexHealthReport.getLeafDocCountInIndex());
        ihr.add("Count of duplicate leaves in the index", indexHealthReport.getDuplicatedLeafInIndex().cardinality());
        if (indexHealthReport.getDuplicatedLeafInIndex().cardinality() > 0)
        {
            ihr.add("First duplicate leaf in the index", "LEAF-" + indexHealthReport.getDuplicatedLeafInIndex().nextSetBit(0L));
        }
        ihr.add("Last index commit time", indexHealthReport.getLastIndexedCommitTime());
        Date lastDate = new Date(indexHealthReport.getLastIndexedCommitTime());
        ihr.add("Last Index commit date", CachingDateFormat.getDateFormat().format(lastDate));
        ihr.add("Last TX id before holes", indexHealthReport.getLastIndexedIdBeforeHoles());
        return ihr;
    }

    /**
     * Note files can alter due to background processes so file not found is Ok
     * 
     * @param srcDir
     * @param destDir
     * @param preserveFileDate
     * @throws IOException
     */
    private void copyDirectory(File srcDir, File destDir, boolean preserveFileDate) throws IOException
    {
        if (destDir.exists())
        {
            throw new IOException("Destination should be created from clean");
        }
        else
        {
            if (!destDir.mkdirs())
            {
                throw new IOException("Destination '" + destDir + "' directory cannot be created");
            }
            if (preserveFileDate)
            {
                // OL if file not found so does not need to check
                destDir.setLastModified(srcDir.lastModified());
            }
        }
        if (!destDir.canWrite())
        {
            throw new IOException("No acces to destination directory" + destDir);
        }

        File[] files = srcDir.listFiles();
        if (files != null)
        {
            for (int i = 0; i < files.length; i++)
            {
                File currentCopyTarget = new File(destDir, files[i].getName());
                if (files[i].isDirectory())
                {
                    copyDirectory(files[i], currentCopyTarget, preserveFileDate);
                }
                else
                {
                    copyFile(files[i], currentCopyTarget, preserveFileDate);
                }
            }
        }
    }

    private void copyFile(File srcFile, File destFile, boolean preserveFileDate) throws IOException
    {
        try
        {
            if (destFile.exists())
            {
                throw new IOException("File shoud not exist " + destFile);
            }

            FileInputStream input = new FileInputStream(srcFile);
            try
            {
                FileOutputStream output = new FileOutputStream(destFile);
                try
                {
                    copy(input, output);
                }
                finally
                {
                    try
                    {
                        output.close();
                    }
                    catch (IOException io)
                    {

                    }
                }
            }
            finally
            {
                try
                {
                    input.close();
                }
                catch (IOException io)
                {

                }
            }

            // check copy
            if (srcFile.length() != destFile.length())
            {
                throw new IOException("Failed to copy full from '" + srcFile + "' to '" + destFile + "'");
            }
            if (preserveFileDate)
            {
                destFile.setLastModified(srcFile.lastModified());
            }
        }
        catch (FileNotFoundException fnfe)
        {
            fnfe.printStackTrace();
        }
    }

    public int copy(InputStream input, OutputStream output) throws IOException
    {
        byte[] buffer = new byte[2048 * 4];
        int count = 0;
        int n = 0;
        while ((n = input.read(buffer)) != -1)
        {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    public void deleteDirectory(File directory) throws IOException
    {
        if (!directory.exists())
        {
            return;
        }
        if (!directory.isDirectory())
        {
            throw new IllegalArgumentException("Not a directory " + directory);
        }

        File[] files = directory.listFiles();
        if (files == null)
        {
            throw new IOException("Failed to delete director - no access" + directory);
        }

        for (int i = 0; i < files.length; i++)
        {
            File file = files[i];

            if (file.isDirectory())
            {
                deleteDirectory(file);
            }
            else
            {
                if (!file.delete())
                {
                    throw new IOException("Unable to delete file: " + file);
                }
            }
        }

        if (!directory.delete())
        {
            throw new IOException("Unable to delete directory " + directory);
        }
    }

    class SolrServletRequest extends SolrQueryRequestBase
    {
        public SolrServletRequest(SolrCore core, HttpServletRequest req)
        {
            super(core, new MultiMapSolrParams(Collections.<String, String[]> emptyMap()));
        }
    }

    public static void main(String[] args)
    {
        AlfrescoCoreAdminHandler handler = new AlfrescoCoreAdminHandler();
        String[] toSort = handler.orderLocalisedNames;
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        Arrays.sort(toSort, collator);
        System.out.println(Locale.ENGLISH);
        for (int i = 0; i < toSort.length; i++)
        {
            System.out.println(toSort[i]);
        }

        collator = Collator.getInstance(Locale.FRENCH);
        Arrays.sort(toSort, collator);
        System.out.println(Locale.FRENCH);
        for (int i = 0; i < toSort.length; i++)
        {
            System.out.println(toSort[i]);
        }

        collator = Collator.getInstance(Locale.GERMAN);
        Arrays.sort(toSort, collator);
        System.out.println(Locale.GERMAN);
        for (int i = 0; i < toSort.length; i++)
        {
            System.out.println(toSort[i]);
        }

        collator = Collator.getInstance(new Locale("sv"));
        Arrays.sort(toSort, collator);
        System.out.println(new Locale("sv"));
        for (int i = 0; i < toSort.length; i++)
        {
            System.out.println(toSort[i]);
        }

    }
}
