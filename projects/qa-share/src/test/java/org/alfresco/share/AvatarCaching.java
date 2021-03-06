/*
 * Copyright (C) 2005-2014 Alfresco Software Limited.
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
package org.alfresco.share;

import static org.alfresco.po.share.NewGroupPage.ActionButton.CREATE_GROUP;
import static org.alfresco.po.share.enums.Dashlets.MY_PROFILE;
import static org.alfresco.po.share.enums.Dashlets.SITE_MEMBERS;
import static org.alfresco.po.share.enums.UserRole.COLLABORATOR;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;
import java.net.URLEncoder;

import net.lightbody.bmp.core.har.Har;
import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.core.har.HarRequest;
import net.lightbody.bmp.core.har.HarResponse;
import net.lightbody.bmp.proxy.ProxyServer;

import org.alfresco.po.share.AddUserToGroupForm;
import org.alfresco.po.share.DashBoardPage;
import org.alfresco.po.share.GroupsPage;
import org.alfresco.po.share.LoginPage;
import org.alfresco.po.share.NewGroupPage;
import org.alfresco.po.share.PeopleFinderPage;
import org.alfresco.po.share.SharePage;
import org.alfresco.po.share.UserSearchPage;
import org.alfresco.po.share.enums.Dashlets;
import org.alfresco.po.share.site.CustomiseSiteDashboardPage;
import org.alfresco.po.share.site.InviteMembersPage;
import org.alfresco.po.share.site.SiteDashboardPage;
import org.alfresco.po.share.site.SiteMembersPage;
import org.alfresco.po.share.site.contentrule.FolderRulesPage;
import org.alfresco.po.share.site.contentrule.createrules.CreateRulePage;
import org.alfresco.po.share.site.contentrule.createrules.EmailMessageForm;
import org.alfresco.po.share.site.contentrule.createrules.selectors.impl.ActionSelectorEnterpImpl;
import org.alfresco.po.share.site.document.DocumentLibraryPage;
import org.alfresco.po.share.site.document.FileDirectoryInfo;
import org.alfresco.po.share.user.EditProfilePage;
import org.alfresco.po.share.user.MyProfilePage;
import org.alfresco.share.util.AbstractUtils;
import org.alfresco.share.util.JmxUtils;
import org.alfresco.share.util.ShareUser;
import org.alfresco.share.util.ShareUserDashboard;
import org.alfresco.share.util.ShareUserMembers;
import org.alfresco.share.util.ShareUserSitePage;
import org.alfresco.share.util.SiteUtil;
import org.alfresco.share.util.api.CreateUserAPI;
import org.alfresco.test.FailedTestListener;
import org.alfresco.webdrone.exception.PageRenderTimeException;
import org.apache.log4j.Logger;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

/**
 * @author Aliaksei Boole
 */
@Listeners(FailedTestListener.class)
public class AvatarCaching extends AbstractUtils
{
    private static final Logger logger = Logger.getLogger(AvatarCaching.class);
    private ProxyServer proxyServer;
    private final static String TEST_FILE = "test.txt";
    private final static String AVATAR_FILE = "app-logo-48.png";

    @Override
    @BeforeClass(alwaysRun = true)
    public void setup()
    {
        testName = this.getClass().getSimpleName();
        logger.info("[Suite ] : Start Tests in: " + testName);
        Proxy proxy = initProxy();
        initDrone(proxy);
    }

    private Proxy initProxy()
    {
        try
        {
            proxyServer = new ProxyServer(9978);
            proxyServer.start();
            return proxyServer.seleniumProxy();
        }
        catch (Exception e)
        {
            logger.error("Can't start proxy.", e);
            fail("Can't start proxy.", e);
        }
        return null;
    }

    private void initDrone(Proxy proxy)
    {
        DesiredCapabilities desiredCapabilities = new DesiredCapabilities();
        desiredCapabilities.setCapability(CapabilityType.PROXY, proxy);
        WebDriver driver = new FirefoxDriver(desiredCapabilities);
        super.setupCustomDrone(driver);
    }

    private boolean checkRequestResponse(Har har, String requestUrlRegExp, long expectedStatusCode)
    {
        boolean requestFound = false;
        for (HarEntry harEntry : har.getLog().getEntries())
        {
            HarRequest harRequest = harEntry.getRequest();
            HarResponse harResponse = harEntry.getResponse();
            String requestUrl = harRequest.getUrl();
            if (requestUrl.matches(requestUrlRegExp))
            {
                assertEquals(harResponse.getStatus(), expectedStatusCode, "Wrong http status for request: " + requestUrl);
                requestFound = true;
                break;
            }
        }
        return requestFound;
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13951() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        editProfilePage.getNav().selectMyDashBoard();
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        File file = newFile(TEST_FILE, "gogno from hell");
        ShareUserSitePage.uploadFile(customDrone, file).render();
    }

    /**
     * My Activities dashlet
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13951()
    {
        final String AVATAR_THUMBNAIL_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/thumbnail/avatar";
        final String AVATAR_THUMBNAIL_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/thumbnail/avatar";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        customDrone.navigateTo(shareUrl);
        LoginPage lp = new LoginPage(customDrone);
        lp.loginAs(testUser, DEFAULT_PASSWORD);
        //DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        DashBoardPage dashBoardPage = customDrone.getCurrentPage().render();
        dashBoardPage.getDashlet(DASHLET_ACTIVITIES).render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        dashBoardPage.getNav().selectMyDashBoard().render();
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13952() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        editProfilePage.getNav().selectMyDashBoard();
        ShareUser.openUserDashboard(customDrone);
        ShareUserDashboard.addDashlet(customDrone, MY_PROFILE);
    }

    /**
     * My Profile dashlet
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13952()
    {
        final String AVATAR_PROFILE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+";
        final String AVATAR_PROFILE_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        dashBoardPage.getDashlet(DASHLET_ACTIVITIES).render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_PROFILE_CLOUD_REGEXP, 200), "Request[" + AVATAR_PROFILE_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_PROFILE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_PROFILE_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        dashBoardPage.getNav().selectMyDashBoard().render();
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_PROFILE_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_PROFILE_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13953() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        editProfilePage.getNav().selectMyDashBoard();
        ShareUser.openUserDashboard(customDrone);
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        File file = newFile(TEST_FILE, "gogno from hell");
        ShareUserSitePage.uploadFile(customDrone, file).render();
        SiteDashboardPage siteDashboardPage = SiteUtil.openSiteDashboard(customDrone, siteName);
        CustomiseSiteDashboardPage customiseSiteDashboardPage = siteDashboardPage.getSiteNav().selectCustomizeDashboard().render();
        customiseSiteDashboardPage.selectChangeLayout().selectNewLayout(4);
        customiseSiteDashboardPage.removeAllDashletsWithOutConfirm();
        try
        {
            customiseSiteDashboardPage.addDashlet(Dashlets.SITE_ACTIVITIES, 1);
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
    }

    /**
     * Site Activities dashlet
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13953()
    {
        final String AVATAR_THUMBNAIL_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/thumbnail/avatar";
        final String AVATAR_THUMBNAIL_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/thumbnail/avatar";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        try
        {
            SiteUtil.openSiteDashboard(customDrone, siteName);
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
        SiteDashboardPage siteDashboardPage = new SiteDashboardPage(customDrone);
        siteDashboardPage.getDashlet(SITE_ACTIVITIES).render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        try
        {
            siteDashboardPage.getSiteNav().selectSiteDashBoard();
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
        siteDashboardPage = new SiteDashboardPage(customDrone);
        siteDashboardPage.getDashlet(SITE_ACTIVITIES).render();
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13954() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        editProfilePage.getNav().selectMyDashBoard();
        ShareUser.openUserDashboard(customDrone);
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        File file = newFile(TEST_FILE, "gogno from hell");
        ShareUserSitePage.uploadFile(customDrone, file).render();
        SiteDashboardPage siteDashboardPage = SiteUtil.openSiteDashboard(customDrone, siteName);
        CustomiseSiteDashboardPage customiseSiteDashboardPage = siteDashboardPage.getSiteNav().selectCustomizeDashboard().render();
        customiseSiteDashboardPage.selectChangeLayout().selectNewLayout(4);
        customiseSiteDashboardPage.removeAllDashletsWithOutConfirm();
        try
        {
            customiseSiteDashboardPage.addDashlet(Dashlets.SITE_MEMBERS, 1);
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
    }

    /**
     * Site Members dashlet
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13954()
    {
        final String AVATAR_PROFILE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+";
        final String AVATAR_PROFILE_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/profile/avatar/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String siteName = getSiteName(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        try
        {
            SiteUtil.openSiteDashboard(customDrone, siteName);
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
        SiteDashboardPage siteDashboardPage = new SiteDashboardPage(customDrone);
        siteDashboardPage.getDashlet(SITE_MEMBERS.getDashletName()).render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_PROFILE_CLOUD_REGEXP, 200), "Request[" + AVATAR_PROFILE_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_PROFILE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_PROFILE_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        try
        {
            siteDashboardPage.getSiteNav().selectSiteDashBoard();
        }
        catch (PageRenderTimeException e)
        {
            logger.info("SiteDashBoard don't render in time. It's normal. Broken logic in render method.");
        }
        siteDashboardPage = new SiteDashboardPage(customDrone);
        siteDashboardPage.getDashlet(SITE_MEMBERS.getDashletName()).render();
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_PROFILE_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_PROFILE_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13955() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser2);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
    }

    /**
     * People Finder
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13955()
    {
        final String AVATAR_THUMBNAIL_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String AVATAR_THUMBNAIL_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        PeopleFinderPage peopleFinderPage = dashBoardPage.getNav().selectPeople();
        peopleFinderPage.searchFor(testUser).render();
        webDriverWait(customDrone, 2000);
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        peopleFinderPage.searchFor(testUser).render();
        webDriverWait(customDrone, 2000);
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13956() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
    }

    /**
     * User Profile
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13956()
    {
        final String AVATAR_THUMBNAIL_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=force";
        final String AVATAR_THUMBNAIL_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=force";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile().render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        myProfilePage.openEditProfilePage().clickCancel();
        webDriverWait(customDrone, 2000);
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13957() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser2);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUserMembers.inviteUserToSiteWithRole(customDrone, testUser, testUser2, siteName, COLLABORATOR);
    }

    /**
     * Site Members
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13957() throws Exception
    {
        final String AVATAR_NODE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String AVATAR_NODE_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/thumbnails/avatar\\?c=queue&ph=true";

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        proxyServer.newHar(serverAddress);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        SiteDashboardPage siteDashboardPage = SiteUtil.openSiteDashboard(customDrone, siteName);
        SiteMembersPage siteMembersPage = siteDashboardPage.getSiteNav().selectMembers();
        siteMembersPage.searchUser(testUser2);
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_NODE_CLOUD_REGEXP, 200), "Request[" + AVATAR_NODE_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        siteMembersPage.searchUser(testUser2);
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertFalse(checkRequestResponse(har, AVATAR_NODE_CLOUD_REGEXP, 304), "Found 304 Request");
        }
        else
        {
            assertFalse(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 304), "Found 304 Request");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching" })
    public void dataPrep_AONE_13958() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser2);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUserMembers.inviteUserToSiteWithRole(customDrone, testUser, testUser2, siteName, COLLABORATOR);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        File file = newFile(TEST_FILE, "gogno from hell");
        ShareUserSitePage.uploadFile(customDrone, file).render();
        ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        ShareUser.openDocumentDetailPage(customDrone, TEST_FILE);
        ShareUserSitePage.uploadNewVersionFromDocDetail(customDrone, true, TEST_FILE, TEST_FILE);
    }

    /**
     * Version History
     */
    @Test(groups = { "Share", "NonGrid" })
    public void AONE_13958() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);
        String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        String userNameInUrl = URLEncoder.encode(testUser2, "UTF-8").replace("%", "\\%").replace(".", "\\.");
        final String AVATAR_THUMBNAIL_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/slingshot/profile/avatar/" + userNameInUrl + "/thumbnail/avatar32";
        final String AVATAR_THUMBNAIL_CLOUD_REGEXP = "https?://.+/share/\\w+\\.\\w+/proxy/alfresco/slingshot/profile/avatar/" + userNameInUrl + "/thumbnail/avatar32";

        proxyServer.newHar(serverAddress);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        ShareUser.openDocumentDetailPage(customDrone, TEST_FILE).render();
        Har har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }

        proxyServer.newHar(serverAddress);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        ShareUser.openDocumentDetailPage(customDrone, TEST_FILE);
        har = proxyServer.getHar();
        if (alfrescoVersion.isCloud())
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_CLOUD_REGEXP, 304), "Request[" + AVATAR_THUMBNAIL_CLOUD_REGEXP + "] don't found.");
        }
        else
        {
            assertTrue(checkRequestResponse(har, AVATAR_THUMBNAIL_ENTERPRISE_REGEXP, 304), "Request[" + AVATAR_THUMBNAIL_ENTERPRISE_REGEXP + "] don't found.");
        }
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching", "EnterpriseOnly" })
    public void dataPrep_AONE_15094() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testGroup = getGroupName(testName);

        SharePage page = ShareUser.login(customDrone, ADMIN_USERNAME, ADMIN_PASSWORD).render();
        GroupsPage groupsPage = page.getNav().getGroupsPage();
        groupsPage = groupsPage.clickBrowse();
        NewGroupPage newGroupPage = groupsPage.navigateToNewGroupPage().render();
        newGroupPage.createGroup(testGroup, testGroup, CREATE_GROUP).render();
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);

        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
    }

    @Test(groups = { "Share", "NonGrid", "EnterpriseOnly" })
    @SuppressWarnings("deprecation")
    public void AONE_15094() throws Exception
    {
        final String AVATAR_NODE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testGroup = getGroupName(testName);

        SharePage page = ShareUser.login(customDrone, ADMIN_USERNAME, ADMIN_PASSWORD).render();
        GroupsPage groupsPage = page.getNav().getGroupsPage().clickBrowse();
        AddUserToGroupForm addUserToGroupForm = groupsPage.navigateToAddUserForm(testGroup);

        proxyServer.newHar(serverAddress);
        addUserToGroupForm.searchUser(testUser).waitUntilAlert().render();
        Har har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");

        page.getNav().getGroupsPage().clickBrowse();
        addUserToGroupForm = groupsPage.navigateToAddUserForm(testGroup);

        proxyServer.newHar(serverAddress);
        addUserToGroupForm.searchUser(testUser).waitUntilAlert().render();
        har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 304), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching", "EnterpriseOnly" })
    public void dataPrep_AONE_15095() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
    }

    @Test(groups = { "Share", "NonGrid", "EnterpriseOnly" })
    public void AONE_15095() throws Exception
    {
        final String AVATAR_NODE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);

        SharePage page = ShareUser.login(customDrone, ADMIN_USERNAME, ADMIN_PASSWORD).render();
        UserSearchPage userSearchPage = page.getNav().getUsersPage().render();

        proxyServer.newHar(serverAddress);
        userSearchPage.searchFor(testUser).waitUntilAlert();
        Har har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");

        userSearchPage = page.getNav().getUsersPage().render();

        proxyServer.newHar(serverAddress);
        userSearchPage.searchFor(testUser).waitUntilAlert();
        har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 304), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching", "EnterpriseOnly" })
    public void dataPrep_AONE_15096() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser2);

        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        SiteUtil.createSite(customDrone, siteName, siteName);
    }

    @Test(groups = { "Share", "NonGrid", "EnterpriseOnly" })
    public void AONE_15096() throws Exception
    {
        final String AVATAR_NODE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);

        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        SiteDashboardPage siteDashboardPage = SiteUtil.openSiteDashboard(customDrone, siteName);
        InviteMembersPage inviteMembersPage = siteDashboardPage.getSiteNav().selectInvite();

        proxyServer.newHar(serverAddress);
        inviteMembersPage.searchUser(testUser2);
        Har har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");

        siteDashboardPage = SiteUtil.openSiteDashboard(customDrone, siteName);
        inviteMembersPage = siteDashboardPage.getSiteNav().selectInvite();

        proxyServer.newHar(serverAddress);
        inviteMembersPage.searchUser(testUser2);
        har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 304), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");
    }

    @Test(groups = { "Share", "NonGrid", "DataPrepAvatarCaching", "EnterpriseOnly" })
    public void dataPrep_AONE_15097() throws Exception
    {
        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);
        String folderName = getFolderName(testName);

        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser);
        CreateUserAPI.CreateActivateUser(customDrone, ADMIN_USERNAME, testUser2);

        DashBoardPage dashBoardPage = ShareUser.login(customDrone, testUser2, DEFAULT_PASSWORD).render();
        MyProfilePage myProfilePage = dashBoardPage.getNav().selectMyProfile();
        EditProfilePage editProfilePage = myProfilePage.openEditProfilePage();
        File avatar = new File(DATA_FOLDER + SLASH + AVATAR_FILE);
        editProfilePage.uploadAvatar(avatar);
        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        SiteUtil.createSite(customDrone, siteName, siteName);
        ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        ShareUserSitePage.createFolder(customDrone, folderName, folderName);
    }

    @Test(groups = { "Share", "NonGrid", "EnterpriseOnly" })
    public void AONE_15097() throws Exception
    {
        final String AVATAR_NODE_ENTERPRISE_REGEXP = "https?://.+/share/proxy/alfresco/api/node/workspace/SpacesStore/\\w+\\-\\w+\\-\\w+\\-\\w+\\-\\w+/content/thumbnails/avatar\\?c=queue&ph=true";
        final String serverAddress = JmxUtils.getAddress(dronePropertiesMap.get(customDrone).getShareUrl());

        String testName = getTestName();
        String testUser = getUserNameFreeDomain(testName);
        String testUser2 = getUserNameFreeDomain(testName + "2");
        String siteName = getSiteName(testName);
        String folderName = getFolderName(testName);

        ShareUser.login(customDrone, testUser, DEFAULT_PASSWORD);
        DocumentLibraryPage documentLibraryPage = ShareUser.openSitesDocumentLibrary(customDrone, siteName);
        FileDirectoryInfo fileDirectoryInfo = documentLibraryPage.getFileDirectoryInfo(folderName);
        FolderRulesPage folderRulesPage = fileDirectoryInfo.selectManageRules().render();
        CreateRulePage createRulePage = folderRulesPage.openCreateRulePage();
        ActionSelectorEnterpImpl actionSelectorEnterp = createRulePage.getActionOptionsObj();
        EmailMessageForm emailMessageForm = actionSelectorEnterp.selectSendEmail();

        proxyServer.newHar(serverAddress);
        emailMessageForm.addUserToRecipients(testUser2);
        emailMessageForm.removeUserFromRecipients(testUser2);
        Har har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 200), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");

        emailMessageForm.clickClose();
        folderRulesPage = createRulePage.clickCancelButton();
        createRulePage = folderRulesPage.openCreateRulePage();
        actionSelectorEnterp = createRulePage.getActionOptionsObj();
        emailMessageForm = actionSelectorEnterp.selectSendEmail();

        proxyServer.newHar(serverAddress);
        emailMessageForm.addUserToRecipients(testUser2);
        emailMessageForm.clickClose();
        har = proxyServer.getHar();
        assertTrue(checkRequestResponse(har, AVATAR_NODE_ENTERPRISE_REGEXP, 304), "Request[" + AVATAR_NODE_ENTERPRISE_REGEXP + "] don't found.");
    }

    @Override
    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        super.tearDown();
        if (proxyServer != null)
        {
            try
            {
                proxyServer.stop();
            }
            catch (Exception e)
            {
                logger.error("Proxy don't stop.", e);
            }
        }
    }
}
