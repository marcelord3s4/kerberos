/*
 * Copyright 2018 by Rutgers, the State University of New Jersey
 * All Rights Reserved.
 *
 * Permission to use, copy, modify, and
 * distribute this software and its documentation for any purpose and
 * without fee is hereby granted, provided that the above copyright
 * notice appear in all copies and that both that copyright notice and
 * this permission notice appear in supporting documentation, and that
 * the name of Rutgers not be used in advertising or publicity pertaining
 * to distribution of the software without specific, written prior
 * permission.  Furthermore if you modify this software you must label
 * your software as modified software and not distribute it in such a
 * fashion that it might be confused with the original Rutgers software.
 * Rutgers makes no representations about the suitability of
 * this software for any purpose.  It is provided "as is" without express
 * or implied warranty.
 */

// lets a user display hosts that they manage and add new hosts

package application;

import java.util.List;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.security.auth.*;
import javax.security.auth.callback.*;
import javax.security.auth.login.*;
import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.*;
import javax.security.auth.kerberos.KerberosTicket;
import com.sun.security.auth.callback.TextCallbackHandler;
import java.util.Hashtable;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringEscapeUtils;
import java.net.URLEncoder;
import common.lu;
import common.utils;
import common.JndiAction;
import common.docommand;
import Activator.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Controller
public class HostsController {

    @Autowired
    private LoginController loginController;

    public String filtername(String s) {
	if (s == null)
	    return null;
	String ret = s.replaceAll("[^-_.a-z0-9]","");
	if (ret.equals(""))
	    return null;
	return ret;
    }

    // this class is used to set up a configuration that uses the principal http/services.cs.rutgers.edu
    // It is passed to LoginContext to generate a subject. Most documentation says that the
    // info here has to go into a file, but it's a lot easier to do it in code.

    class ServicesConfiguration extends Configuration { 
        private String cc;
 
        public ServicesConfiguration(String cc) { 
            this.cc = cc;
        } 
 
        @Override 
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) { 
            Map<String, String> options = new HashMap<String, String>(); 
            options.put("useKeyTab", "true"); 
	    options.put("principal", Config.getConfig().servicesprincipal); 
            options.put("refreshKrb5Config", "true"); 
	    options.put("keyTab", "/etc/krb5.keytab.services");
 
            return new AppConfigurationEntry[]{ 
		new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
					  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, 
					  options),}; 
        } 
    } 

    public ServicesConfiguration makeServicesConfiguration(String cc) {
	return new ServicesConfiguration(cc);
    }

    public String showError(String message, HttpServletRequest request, HttpServletResponse response, Model model) {
	List<String> messages = new ArrayList<String>();
	messages.add("Session has expired");
	model.addAttribute("messages", messages);
	return loginController.loginGet("hosts", request, response, model); 
    }

    // show info for current user
    @GetMapping("/hosts/showhosts")
    public String hostsGet(HttpServletRequest request, HttpServletResponse response, Model model) {

	Logger logger = null;
	logger = LogManager.getLogger();

	String user = (String)request.getSession().getAttribute("krb5user");

	Subject userSubject = (Subject)request.getSession().getAttribute("krb5subject");
	if (userSubject == null) {
	    List<String> messages = new ArrayList<String>();
	    messages.add("Session has expired");
	    model.addAttribute("messages", messages);
	    return loginController.loginGet("hosts", request, response, model); 
	}

	DirContext ctx = null;

	// query is designed to make sure sort won't blow up. Must have an fqdn property
	common.JndiAction action = new common.JndiAction(new String[]{"(&(managedby=uid=" + user + Config.getConfig().usersuffix+ ")(fqdn=*))", "cn=computers," + Config.getConfig().accountbase, "fqdn"});
	
	Subject.doAs(userSubject, action);
	List<Map<String, List<String>>> hosts = action.data;

	Collections.sort(hosts, (Map<String, List<String>> h1, 
				 Map<String, List<String>> h2) -> 
			 h1.get("fqdn").get(0).compareTo(h2.get("fqdn").get(0)));

	// if we got an error from POST, we might already have messages.
	if (!model.containsAttribute("messages"))
	    model.addAttribute("messages", new ArrayList<String>());	    

	// set up model for JSTL to output
	model.addAttribute("hosts", hosts);
	return "hosts/showhosts";

    }

    @PostMapping("/hosts/showhosts")
    public String hostSubmit(@RequestParam(value="name", required=true) String name,
			     HttpServletRequest request, HttpServletResponse response,
			     Model model) {

	Logger logger = null;
	logger = LogManager.getLogger();

	Subject userSubject = (Subject)request.getSession().getAttribute("krb5subject");
	if (userSubject == null) {
	    List<String> messages = new ArrayList<String>();
	    messages.add("Session has expired");
	    model.addAttribute("messages", messages);
	    return loginController.loginGet("hosts", request, response, model); 
	}

	String user = (String)request.getSession().getAttribute("krb5user");

	common.JndiAction action = new common.JndiAction(new String[]{"(uid=" + user + ")", "", "memberof"});

	Subject.doAs(userSubject, action);

	if (action.val == null || action.val.size() == 0) {
	    List<String> messages = new ArrayList<String>();
	    messages.add("Unable to find you in our system");
	    model.addAttribute("messages", messages);
	    return loginController.loginGet("hosts", request, response, model); 
	}

	boolean canAddHost = true;

	HashMap<String, ArrayList<String>> attrs = null;
	attrs = action.val.get(0);

	ArrayList groups = attrs.get("memberof");
	if (groups != null && groups.contains("cn=user-add-host,cn=groups,cn=accounts,dc=cs,dc=rutgers,dc=edu"))
	    canAddHost = true;

	if (!canAddHost) {
	    List<String> messages = new ArrayList<String>();
	    messages.add("You are not authorized to add hosts");
	    model.addAttribute("messages", messages);
	    return hostsGet(request, response, model); 
	}

	String env[] = {"KRB5CCNAME=/tmp/krb5cc_" + user, "PATH=/bin:/user/bin"};

	logger.info("ipa host-add " + name + " --addattr=nshostlocation=research-user");
	List<String> messages = new ArrayList<String>();
	if (docommand.docommand (new String[]{"/bin/ipa", "host-add", name, "--addattr=nshostlocation=research-user"}, env, messages) != 0) {
	    boolean exists = false;
	    for (String m:messages) {
		// can't add to messages while we're looping over it, so set flag
		if (m.contains("already exists"))
		    exists = true;
	    }
	    if (exists)
		messages.add("Host already exists. If it's not in your list, that means it's managed by someone else. You can request " + Config.getConfig().helpmail + " to add you as a manager.");
	    model.addAttribute("messages", messages);
	    return hostsGet(request, response, model); 
	}

	messages = new ArrayList<String>();
	logger.info("ipa host-mod " + name + " --addattr=managedby=uid=" + user + Config.getConfig().usersuffix);
	if (docommand.docommand (new String[]{"/bin/ipa", "host-mod", name, "--addattr=managedby=uid=" + user + Config.getConfig().usersuffix}, env, messages) != 0) {
	    messages.add("Unable to add you as manager for this system. This should be impossible. Please contact " + Config.getConfig().helpmail);
	    model.addAttribute("messages", messages);
	    return hostsGet(request, response, model); 
	}

	// tell all the kerberos servers to update their firewalls

	String sshenv[] = {"KRB5CCNAME=/tmp/krb5ccservices", "PATH=/bin:/user/bin"};

	// look up kerberos servers in DNS
	String query = "_kerberos._tcp." + Config.getConfig().kerberosdomain;
	Hashtable<String, String> environment = new Hashtable<String, String>();
	environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
	environment.put("java.naming.provider.url", "dns:");
	// do DNS lookup of _kerberos._tcp.cs.rutgers.edu
	try {
	    InitialDirContext dirContext = new InitialDirContext(environment);
	    javax.naming.NamingEnumeration records = dirContext.getAttributes(query, new String[] {"SRV"}).getAll();
	    // iterate over results
	    while (records.hasMore()) {
		javax.naming.directory.Attribute attr = (javax.naming.directory.Attribute) records.next();
		javax.naming.NamingEnumeration addrs = attr.getAll();
		while (addrs.hasMore()) {
		    String addr = (String)addrs.next();
		    // record looks like 0 100 88 krb2.cs.rutgers.edu.
		    // so we need the 4th element
		    String[] hostinfo = addr.split(" ", 4);
		    String host = hostinfo[3];

		    // got it. Now prod the server. See README for how this is set up.
		    // it's a Rube Goldberg contraption that ends up running the firewall update
		    // on all the servers. Just need to ssh to the host. There's a forced command.
		    logger.info("ssh syncipt@" + host);
		    // don't check the results. batch job will fix it up
		    if (docommand.docommand (new String[]{"/bin/ssh", "syncipt@" + host}, sshenv) != 0) {
			logger.info("ssh to kerberos server failed");
		    }
		}
	    }
	} catch (Exception e) {
	    logger.info("attempt to get kerberos server hosts failed " + e);
	}

	return hostsGet(request, response, model);

    }

}
