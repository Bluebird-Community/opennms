<%--

    Licensed to The OpenNMS Group, Inc (TOG) under one or more
    contributor license agreements.  See the LICENSE.md file
    distributed with this work for additional information
    regarding copyright ownership.

    TOG licenses this file to You under the GNU Affero General
    Public License Version 3 (the "License") or (at your option)
    any later version.  You may not use this file except in
    compliance with the License.  You may obtain a copy of the
    License at:

         https://www.gnu.org/licenses/agpl-3.0.txt

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied.  See the License for the specific
    language governing permissions and limitations under the
    License.

--%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn"%>

<%@ page import="org.opennms.web.utils.Bootstrap" %>
<% Bootstrap.with(pageContext)
          .headTitle("Edit Expression Threshold")
          .headTitle("Thresholds")
          .headTitle("Admin")
          .breadcrumb("Admin", "admin/index.jsp")
          .breadcrumb("Threshold Groups", "admin/thresholds/index.jsp")
          .breadcrumb("Edit Group", "admin/thresholds/index.jsp?groupName=${groupName}&editGroup")
          .breadcrumb("Edit Threshold")
          .build(request);
%>
<jsp:directive.include file="/includes/bootstrap.jsp" />

<form name="frm" action="admin/thresholds/index.htm" method="post">
<input type="hidden" name="finishExpressionEdit" value="1"/>
<input type="hidden" name="expressionIndex" value="${expressionIndex}"/>
<input type="hidden" name="groupName" value="${groupName}"/>
<input type="hidden" name="isNew" value="${isNew}"/>
<input type="hidden" name="filterSelected" value="${filterSelected}"/>

<div class="row">
  <div class="col-md-12">
    <div class="card">
      <div class="card-header">
        <span>Edit expression threshold</span>
      </div>
      <table class="table table-sm">
        <tr>
        	<th>Type</th>
        	<th>Expression</th>
        	<th>Datasource type</th>
        	<th>Datasource label</th>
        	<th>Expression label</th>
        </tr>
        	<tr>
                <td>
                    <select name="type" class="form-control custom-select">
                        <c:forEach items="${thresholdTypes}" var="thisType">
                            <c:choose>
                                <c:when test="${expression.type.enumName==thisType}">
                                    <c:set var="selected">selected="selected"</c:set>
                                </c:when>
                                <c:otherwise>
                                    <c:set var="selected" value=""/>
                                </c:otherwise>
                            </c:choose>
                            <option ${selected} value='${thisType}'>${thisType}</option>
                        </c:forEach>
                    </select>
                </td>
        		<td><input type="text" name="expression" class="form-control" size="30" value="${expression.expression}"/></td>
        		<td>
        		   	<select name="dsType" class="form-control custom-select">
        				<c:forEach items="${dsTypes}" var="thisDsType">
       						<c:choose>
      							<c:when test="${expression.dsType==thisDsType.key}">
        							<c:set var="selected">selected="selected"</c:set>
      							</c:when>
    	 						<c:otherwise>
    	    						<c:set var="selected" value=""/>
    	  						</c:otherwise>
    						</c:choose>
    						<option ${selected} value='${thisDsType.key}'>${thisDsType.value}</option>
        				</c:forEach>
        			</select></td>
                <td><input type="text" name="dsLabel" class="form-control" size="30" value="${expression.dsLabel.orElse(null)}"/></td>
                <td><input type="text" name="exprLabel" class="form-control" size="30" value="${expression.exprLabel.orElse(null)}"/></td>
        	</tr>
        </table>
        <table class="table table-sm">
            <tr>
                <th>Value</th>
                <th>Re-arm</th>
                <th>Trigger</th>
            </tr>
            <tr>
                <td><input type="text" name="value" class="form-control" size="60" value="${expression.value}"/></td>
                <td><input type="text" name="rearm" class="form-control" size="60" value="${expression.rearm}"/></td>
                <td><input type="text" name="trigger" class="form-control" size="60" value="${expression.trigger}"/></td>
            </tr>
        </table>
        <table class="table table-sm">
             <tr>
                    <th>Description</th>
                    <th>Triggered UEI</th>
                    <th>Re-armed UEI</th>
            </tr>
        	<tr>
                <td><input type="text" name="description" class="form-control" size="60" value="${expression.description.orElse(null)}"/></td>
                <td><input type="text" name="triggeredUEI" class="form-control" size="60" value="${expression.triggeredUEI.orElse(null)}"/></td>
                <td><input type="text" name="rearmedUEI" class="form-control" size="60" value="${expression.rearmedUEI.orElse(null)}"/></td>
        	</tr>
      </table>
      <div class="card-footer">
        <input type="submit" name="submitAction" class="btn btn-secondary" value="${saveButtonTitle}"/>
        <input type="submit" name="submitAction" class="btn btn-secondary" value="${cancelButtonTitle}"/>
      </div> <!-- card-footer -->
    </div> <!-- panel -->
  </div> <!-- column -->
</div> <!-- row -->
  
<div class="row">
  <div class="col-md-8">
    <div class="card">
      <div class="card-header">
        <span>Resource Filters</span>
      </div>
      <div class="card-body">
        <div class="row">
          <div class="col-sm-4">
            <table class="table table-sm">
              <tr>
                <th>Filter Operator</th>
              </tr>
              <tr>
                <td>
                  <select name="filterOperator" class="form-control custom-select">
                      <c:forEach items="${filterOperators}" var="thisOperator">
                          <c:choose>
                              <c:when test="${expression.filterOperator.enumName==thisOperator}">
                                  <c:set var="selected">selected="selected"</c:set>
                              </c:when>
                              <c:otherwise>
                                  <c:set var="selected" value=""/>
                              </c:otherwise>
                          </c:choose>
                          <option ${selected} value='${thisOperator}'>${thisOperator}</option>
                      </c:forEach>
                  </select>
                </td>
              </tr>
            </table>
          </div> <!-- column -->
        </div> <!-- row -->
        <div class="row">
          <div class="col-md-12">
            <table class="table table-sm">
            <tr><th>Field Name</th><th>Regular Expression</th><th>Actions</th></tr>
              <c:forEach items="${expression.resourceFilters}" var="filter" varStatus="i">
                <tr name="filter.${i.count}">
                    <c:choose>
                      <c:when test="${i.count==filterSelected}">
                        <td><input type="text" name="updateFilterField" class="form-control" size="60" value="${fn:escapeXml(filter.field)}"/></td>
                        <td><input type="text" name="updateFilterRegexp" class="form-control" size="60" value="${fn:escapeXml(filter.content.orElse(null))}"/></td>
                        <td><input type="submit" name="submitAction" class="form-control" value="${updateButtonTitle}" onClick="document.frm.filterSelected.value='${i.count}'"/></td>          
                      </c:when>
                      <c:otherwise>
                        <td class="standard"><input type="text" class="form-control" disabled="disabled" size="60" value="${fn:escapeXml(filter.field)}"/></td>
                        <td class="standard"><input type="text" class="form-control" disabled="disabled" size="60" value="${fn:escapeXml(filter.content.orElse(null))}"/></td>
                        <td><input type="submit" name="submitAction" class="btn btn-secondary" value="${editButtonTitle}" onClick="document.frm.filterSelected.value='${i.count}'"/>
                            <input type="submit" name="submitAction" class="btn btn-secondary" value="${deleteButtonTitle}" onClick="document.frm.filterSelected.value='${i.count}'"/>
                            <input type="submit" name="submitAction" class="btn btn-secondary" value="${moveUpButtonTitle}" onClick="document.frm.filterSelected.value='${i.count}'"/>
                            <input type="submit" name="submitAction" class="btn btn-secondary" value="${moveDownButtonTitle}" onClick="document.frm.filterSelected.value='${i.count}'"/>
                            </td>
                      </c:otherwise>
                    </c:choose>
                </tr>
              </c:forEach>
                <tr>
                    <td><input type="text" name="filterField" class="form-control" size="60"/></td>
                    <td><input type="text" name="filterRegexp" class="form-control" size="60"/></td>
                    <td><input type="submit" name="submitAction" class="btn btn-secondary" value="${addFilterButtonTitle}" onClick="setFilterAction('add')"/></td>
                </tr>
            </table>
          </div> <!-- column -->
        </div> <!-- row -->
      </div> <!-- card-body -->
    </div> <!-- panel -->
  </div> <!-- column -->
</div> <!-- row -->
  
</form>

<div class="row">
  <div class="col-md-12">
    <div class="card">
      <div class="card-header">
        <span>Help</span>
      </div>
      <div class="card-body">
        <p>
        <b>Description</b>: An optional full-text description for the threshold expression, to help identify what is their purpose.<br/>
        <b>Type</b>:<br/>
        &nbsp;&nbsp;<b>high</b>: Triggers when the value of the data source equals or exceeds the "value", and is re-armed when it drops below the "re-arm" value.<br/>
        &nbsp;&nbsp;<b>low</b>: Triggers when the value of the data source drops to or below the "value", and is re-armed when it equals or exceeds the "re-arm" value.<br/>
        &nbsp;&nbsp;<b>relativeChange</b>: Triggers when the change in data source value from one collection to the next is greater than or equal to "value" percent.
          Re-arm and trigger are not used.<br/>
        &nbsp;&nbsp;<b>absoluteChange</b>: Triggers when the value changes by the specified amount or greater.  Re-arm and trigger are not used.<br/>
        &nbsp;&nbsp;<b>rearmingAbsoluteChange</b>: Like absoluteChange, Triggers when the value changes by the specified amount or greater.  However,
          the "trigger" is used to re-arm the event after so many iterations with an unchanged delta.  Re-arm is not used.<br/>
        <b>Expression</b>: A  mathematical expression involving datasource names which will be evaluated and compared to the threshold values<br/>
        <b>Data source type</b>: Node for "node-level" data items, and "interface" for interface-level items.  <br/>
        <b>Datasource label</b>: The name of the collected "string" type data item to use as a label when reporting this threshold<br/>
        <b>Expression label</b>: A short human-readable description of the threshold expression<br/>
        <b>Value</b>: Use depends on the type of threshold<br/>
        <b>Re-arm</b>: Use depends on the type of threshold; it is unused/ignored for relativeChange thresholds<br/>
        <b>Trigger</b>: The number of times the threshold must be "exceeded" in a row before the threshold will be triggered.  Not used for relativeChange thresholds.<br/>
        <b>Triggered UEI</b>: A custom UEI to send into the events system when this threshold is triggered.  If left blank, it defaults to the standard thresholds UEIs.<br/>
        <b>Rearmed UEI</b>: A custom UEI to send into the events system when this threshold is re-armed.  If left blank, it defaults to the standard thresholds UEIs.<br/>
        <b>Example UEIs</b>: A typical UEI is of the format <i>"uei.opennms.org/&lt;category&gt;/&lt;name&gt;"</i>.  It is recommended that when creating custom UEIs for thresholds,<br/>
        you use a one-word version of your company name as the category to avoid name conflicts.  The "name" portion is up to you.<br/>
        <b>Filter Operator</b>: Define the logical function that will be applied over the thresholds filters to determinate if the threshold will be applied or not.<br />
        <b>Filters</b>: Only apply for interfaces and Generic Resources. They are applied in order.<br/>
        &nbsp;&nbsp;<b>operator=OR</b>: if the resource match any of them, the threshold will be processed.<br/>
        &nbsp;&nbsp;<b>operator=AND</b>: the resource must match all the filters.
        </p>
      </div> <!-- card-body -->
    </div> <!-- panel -->
  </div> <!-- column -->
</div> <!-- row -->

<jsp:include page="/includes/bootstrap-footer.jsp" flush="false"/>
