<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
<%@ taglib prefix = "c" uri = "http://java.sun.com/jsp/jstl/core" %>
<%@ page import="javax.security.auth.*" %>
<%@ page import="javax.security.auth.callback.*" %>
<%@ page import="javax.security.auth.login.*" %>
<%@ page import="javax.naming.*" %>
<%@ page import="javax.naming.directory.*" %>
<%@ page import="javax.naming.ldap.*" %>
<%@ page import="com.sun.security.auth.callback.TextCallbackHandler" %>
<%@ page import="java.util.Hashtable" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Set" %>
<%@ page import="org.apache.commons.lang3.StringEscapeUtils" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="common.lu" %>
<%@ page import="common.utils" %>
<%@ page import="common.JndiAction" %>
<%@ page import="Activator.Config" %>

<head><link href="../usertool.css" rel="stylesheet" type="text/css">
<script type="text/javascript" src="../jquery-3.2.1.min.js" ></script>
<script type="text/javascript">
function checknewmember() {
	 $(".newmember").last().parent().after("<br/><label>User name<span class=\"hidden\"> to add as member</span>: <input class=\"newmember\" type=\"text\" name=\"newmember\"></label>");
	 $(".newmember").off('input', checknewmember);
	 $(".newmember").last().on('input', checknewmember);
 };

$(document).ready(function(){
	 $(".newmember").on('input', checknewmember);
    });

function checknewowner() {
	 $(".addowner").last().parent().after("<br/><label>User name<span class=\"hidden\"> to add as owner</span>: <input class=\"addowner\" type=\"text\" name=\"newowner\"></label>");
	 $(".addowner").off('input', checknewowner);
	 $(".addowner").last().on('input', checknewowner);
 };

function deleteMember(event) {
  var member = $(event.target).next().val();
  if (!confirm("Are you sure you want to delete this member?"))
    return;
  $("#deleteInput").val(member);
  $("#deleteSubmit").click();
}

function deleteMemberKeyPress(event) {
  // Check to see if space or enter were pressed
  if (event.keyCode === 32 || event.keyCode === 13) {
    // Prevent the default action to stop scrolling when space is pressed
    event.preventDefault();
    deleteMember(event);
  }
}


function deleteOwner(event) {
  var owner = $(event.target).next().val();
  if (!confirm("Are you sure you want to delete this owner?"))
    return;
  $("#deleteOwnerInput").val(owner);
  $("#deleteOwnerSubmit").click();
}

function deleteOwnerKeyPress(event) {
  // Check to see if space or enter were pressed
  if (event.keyCode === 32 || event.keyCode === 13) {
    // Prevent the default action to stop scrolling when space is pressed
    event.preventDefault();
    deleteOwner(event);
  }
}

$(document).ready(function(){
    $(".addowner").on('input', checknewowner);
    $(".deleteMemberButton").click(deleteMember);
    $(".deleteMemberButton").keypress(deleteMemberKeyPress);
    $(".deleteOwnerButton").click(deleteOwner);
    $(".deleteOwnerButton").keypress(deleteOwnerKeyPress);
    });



</script>
</head>
<% String gname = request.getParameter("name"); %>

<%

 // This module uses Kerberized LDAP. The credentials are part of a Subject, which is stored in the session.
 // This JndiAction junk is needed to execute the LDAP code in a context that's authenticated by
 // that Subject.

// To separate logic from display, I've implemented my own API on top of the Sun LDAP code
// It uses a class JndiAction that does an LDAP query and returns
//   ArrayList<HashMap<String, ArrayList<String>>>
// This is a list of things found by the query. For a lookup of a specific user or group the list will have just one member.
// Each memory is a hashmap, with the key being attributes and the value a list of results.
// E.g. map.get("uid") would get you the value of the uid attribute. Because some attributes can have more
// than one value, the map returns a list of strings, not just one.
//
// There are set of conveninece methods in common.lu:
// lu.oneVal can be used to return one value for attributes that have only one value, e.g. 
//    String gid = lu.oneVal(attrs.get("gid"));
//   the advatnage over attrs.get("gid").get(0) is that it won't blow up if there's no value. It returns null, so
//   you should normally check with hasVal first
// lu.hasVal checks whether the value is non-null and has at least one item
// lu.valList return the list of values. lu.valList(attrs.get("member"));
//    all it does it protect against nulls, so if there's no member attribute you get an empty list rather than null
// lu.esc is just an abbreviatio for the incredibly verbose StringEscapeUtils.escapeHtml4
// lu.dn2user converts a dn to a username. If the dn starts with uid=XXXX, it returns XXXX. 
//    otherwise it returns the whole dn

// In case you're not familiar with JSP syntax, <% introduces full java logic. Use it for if tests, for loops, etc.
// <%= prints a value. It's like <% out.println(  

Subject subject = (Subject)request.getSession().getAttribute("krb5subject");
if (subject == null) {
    out.println("<p>Session has expired<p><a href=\"login.jsp\"> Try again</a>");
    return;
}

// This acton isn't done until it's called by doAs
common.JndiAction action = new common.JndiAction(new String[]{"(&(objectclass=groupofnames)(cn=" + gname + "))", "", "cn", "member", "host", "businessCategory", "dn", "gidNumber", "owner", "creatorsName"});

// this is part of the Kerberos support. Subject is the internal data structure representing a Kerberos ticket.
// doas does an action authenticated as that subject. The action has to be a JndiAction. I supply a JndiAction does does
// an LDAP query, but you could do anything that uses GSSAPI authentication.
Subject.doAs(subject, action);

if (action.val.size() != 1) {
    out.println("<p> Group not found.");
    return;
}

HashMap<String, ArrayList<String>> attrs = action.val.get(0);

Config aconfig = new Config();
try {
    aconfig.loadConfig();
} catch (Exception e) {
    out.println("<p> Unable to load configuration.");
    return;
}

// set up model for JSTL to display

pageContext.setAttribute("gname", gname);
pageContext.setAttribute("clusters", aconfig.clusters);
pageContext.setAttribute("group", attrs);

%>

<div id="masthead"></div>
<div id="main">
<a href="../"> Account Management</a> | <a href="showgroups.jsp">Group list</a>

<h2> Show and Edit Group </h2>


<form action="editgroup.jsp" method="post" id="deleteForm" style="display:none">
<%= utils.getCsrf(request) %>
<input type="text" name="del" id="deleteInput"/>
<input type="hidden" name="groupname" value="<c:out value="${gname}"/>">
<input type="submit" id="deleteSubmit"/>
</form>
<form action="editgroup.jsp" method="post" id="deleteOwnerForm" style="display:none">
<%= utils.getCsrf(request) %>
<input type="text" name="delowner" id="deleteOwnerInput"/>
<input type="hidden" name="groupname" value="<c:out value="${gname}"/>">
<input type="submit" id="deleteOwnerSubmit"/>
</form>

<form action="editgroup.jsp" method="post">
<%= utils.getCsrf(request) %>
<input type="hidden" name="groupname" value="<c:out value="${gname}"/>">
<p> Group: <c:out value="${gname}"/><c:if test="${not empty group.gidnumber}"><c:out value=", ${group.gidnumber[0]} "/></c:if>

<h3>Members</h3>
<div class="inset" style="margin-top:0.5em">
<c:if test="${! empty group.member}">
<c:forEach items="${group.member}" var="mdn">
<c:set var="m" value="${lu.dn2user(mdn)}"/>
<c:out value="${(m)}"/> <img role="button" tabindex="0" style="height:1em;margin-left:1em" src="delete.png" title="Delete member <c:out value="${m}"/>" class="deleteMemberButton"><input type="hidden" name="deleteName" value="<c:out value="${m}"/>"><br>

</c:forEach>
</c:if>

<h4>Add member</h4>
<div class="inset">
<label>User name <span class="hidden"> to add as member</span>: <input class="newmember" type="text" name="newmember"></label> <a href="addpart-lookup.jsp" target="addpart"> Lookup up usser</a><br>

<input type="submit" style="margin-top:0.5em"/>
</div>
</div>
<c:if test="${(! empty group.creatorsname) || (! empty group.owner)}">
<h3 style="margin-top:1.5em">Owners</h3>
<div class="inset" style="margin-top:0.5em">

<c:out value="${lu.dn2user(group.creatorsname[0])}" default=""/><br>
<c:forEach items="${group.owner}" var="odn">
<c:set var="o" value="${lu.dn2user(odn)}"/>
<c:out value="${o}"/> <img role="button" tabindex="0" style="height:1em;margin-left:1em" src="delete.png" title="Delete owner <c:out value="${o}"/>" class="deleteOwnerButton"><input type="hidden" name="deleteOwnerName" value="<c:out value="${o}"/>"><br>
</c:forEach>
</c:if>

<h4>Add Owner</h4>
<div class="inset">
<label>User name<span class="hidden"> to add as owner</span>: <input class="addowner" type="text" name="newowner"></label><br>
<input type="submit" style="margin-top:0.5em"/>
</div>
</div>

<h3>Login Ability</h3>

<div class="inset">
<p><label><input type="checkbox" name="login" ${group.businesscategory.contains("login") ? 'checked="checked"' : ""}> Members of group can login to specified clusters<p>
<c:forEach items="${clusters}" var="c">
<label><input type="checkbox" name="hosts" value="<c:out value="${c.name}"/>" <c:if test="${group.host.contains(c.name)}">checked="checked"</c:if> > <c:out value="${c.name}"/><br>
</c:forEach>

<p>
<input type="submit">
</div>
</form>
