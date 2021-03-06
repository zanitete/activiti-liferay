package net.emforge.activiti.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.emforge.activiti.IdMappingService;
import net.emforge.activiti.dao.WorkflowDefinitionExtensionDao;

import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.bpmn.diagram.ProcessDiagramGenerator;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.activiti.rest.api.ActivitiUtil;
import org.apache.commons.io.IOUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.servlet.BrowserSnifferUtil;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HttpUtil;
import com.liferay.portal.kernel.util.MimeTypesUtil;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;

/** Generates images - most code samples got from ProcessDefinitionImageStreamResourceBuilder class from activiti-explorer application
 * 
 * @author akakunin
 *
 */
public class ImageServlet extends HttpServlet {
	private static final long serialVersionUID = 532117496619322018L;
    private static Log _log = LogFactoryUtil.getLog(ImageServlet.class);

    List<String> historicActivityInstanceList = new ArrayList<String>();
	List<String> highLightedFlows = new ArrayList<String>();
    
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//get params
		Long companyId = GetterUtil.getLong(request.getParameter("companyId"), 0l);
		String workflowName = request.getParameter("workflow");
		Integer workflowVersion = GetterUtil.getInteger(request.getParameter("version"), 0);
		Long workflowTaskId = GetterUtil.getLong(request.getParameter("taskId"), 0l);
		Long processId = GetterUtil.getLong(request.getParameter("processId"), 0l);
		Long workflowInstanceId = GetterUtil.getLong(request.getParameter("workflowInstanceId"), 0l);
		if (processId == 0 && workflowInstanceId != 0) {
			// get application context
			ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(this.getServletContext());
			// get beans
			IdMappingService idMappingService = (IdMappingService)applicationContext.getBean("idMappingService");
			processId = GetterUtil.getLong(workflowInstanceId, 0l);
		}
		
		InputStream is = null;
		if (workflowTaskId > 0l) {
			is = getTaskImage(workflowTaskId);
		} else if (processId > 0l) {
			is = getProcessImage(processId);
		} else {
			is = getWorkflowImage(companyId, workflowName, workflowVersion);
		}
		
		if (is != null) {
			byte[] binaryContent = IOUtils.toByteArray(is);
			
			_log.debug("Image Size: " + binaryContent.length);
			
			try {
				sendFile(request, response, "process.png", binaryContent, MimeTypesUtil.getContentType("process.png"));
			} catch (Exception ex) {
				_log.error("Cannot send response");
			}
		} else {
			super.doGet(request, response);
		}
	}
	
	protected InputStream getTaskImage(Long workflowTaskId) {
		_log.debug("Generate image for task: " + workflowTaskId);
		
		// get application context
		ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(this.getServletContext());

		// get beans
		TaskService taskService = (TaskService)applicationContext.getBean(TaskService.class);
		RepositoryService repositoryService = (RepositoryService)applicationContext.getBean(RepositoryService.class);
		_log.debug("Get app context and beans");

		
		String taskId = String.valueOf(workflowTaskId);
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();

		List<String> taskIds = new ArrayList<String>();
		taskIds.add(task.getTaskDefinitionKey());
		
		String defId = task.getProcessDefinitionId();
		
		// it is important to use this way to get ProcessDEfinition - since in this case readed all required for image generation data
		ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity)(ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService).getDeployedProcessDefinition(defId);
		
		BpmnModel bpmnModel = ActivitiUtil.getRepositoryService().getBpmnModel(processDefinition.getId());
        InputStream resource = ProcessDiagramGenerator.generateDiagram(bpmnModel, "png", taskIds);
		
		return resource;
	}
	
	protected InputStream getProcessImage(Long processInstanceId) {
		
		// get application context
		ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(this.getServletContext());

		// get beans
		RuntimeService runtimeService = (RuntimeService)applicationContext.getBean(RuntimeService.class);
		RepositoryService repositoryService = (RepositoryService)applicationContext.getBean(RepositoryService.class);
		HistoryService historyService = (HistoryService)applicationContext.getBean(HistoryService.class);
		_log.debug("Get app context and beans");
		
		String procId = String.valueOf(processInstanceId);
		if (procId == null) {
			procId = String.valueOf(processInstanceId);
		}
		ProcessInstance inst = runtimeService.createProcessInstanceQuery().processInstanceId(procId).singleResult();
		
		if (inst == null){
			_log.error("Process instance " + procId + " not found");
			return null;
		}
	    ProcessDefinitionEntity processDefinition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService).getDeployedProcessDefinition(inst.getProcessDefinitionId());

	    if (processDefinition != null && processDefinition.isGraphicalNotationDefined()) {
	    	List<String> highLightedActivities = runtimeService.getActiveActivityIds(inst.getId());
	    	
	    	List<String> highLightedFlows = getHighLightedFlows(historyService, processDefinition, procId, highLightedActivities);
		    _log.info("> procId:" + procId + ", flows: " + highLightedFlows);
		    
		    BpmnModel bpmnModel = ActivitiUtil.getRepositoryService().getBpmnModel(processDefinition.getId());
	        InputStream resource = ProcessDiagramGenerator.generateDiagram(bpmnModel, "png", highLightedActivities, highLightedFlows);
		    
		    return resource;
	    } 
	    
	    return null;
	}
	
	private List<String> getHighLightedFlows(HistoryService historyService, ProcessDefinitionEntity processDefinition, String procId, List<String> highLightedActivities) {
		//List<String> highLightedFlows = new ArrayList<String>();
		List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery().processInstanceId(procId).orderByHistoricActivityInstanceStartTime().asc()/*.orderByActivityId().asc()*/.list();
	    //_log.info("--->procId: " + procId);
	    //List<String> historicActivityInstanceList = new ArrayList<String>();
	    for (HistoricActivityInstance hai : historicActivityInstances) {
			//_log.info("id: " + hai.getId() + ", ActivityId:" + hai.getActivityId() + ", ActivityName:" + hai.getActivityName());
			historicActivityInstanceList.add(hai.getActivityId());
		}
	    
	    // add current activities to list
	    historicActivityInstanceList.addAll(highLightedActivities);
	 
	    // activities and their sequence-flows
	    getHighLightedFlows(processDefinition.getActivities());
	    /*
	    for (ActivityImpl activity : processDefinition.getActivities()) {
	      int index = historicActivityInstanceList.indexOf(activity.getId());
	      
	      if (index >=0 && index+1 < historicActivityInstanceList.size()) {
	    	  //_log.info("* actId:" + activity.getId() + ", transitions: " + activity.getOutgoingTransitions());
	    	  List<PvmTransition> pvmTransitionList = activity.getOutgoingTransitions();
	    	  for (PvmTransition pvmTransition: pvmTransitionList) {
	    		  String destinationFlowId = pvmTransition.getDestination().getId();
	    		  //_log.info("- destinationFlowId: " + destinationFlowId + ", + " + historicActivityInstanceList.get(index+1));
	    		  if (destinationFlowId.equals(historicActivityInstanceList.get(index+1))) {
	    			  //_log.info("> actId:" + activity.getId() + ", flow: " + destinationFlowId);
	    			  highLightedFlows.add(pvmTransition.getId());
	    		  }
	    	  }
	      }
	    }
	    */
	    return highLightedFlows;
	}
	
	private void getHighLightedFlows (List<ActivityImpl> activityList) {
		for (ActivityImpl activity : activityList) {
		      //int index = historicActivityInstanceList.indexOf(activity.getId());
		      
		      if (activity.getProperty("type").equals("subProcess")) {
		    	  // get flows for the subProcess
		    	  getHighLightedFlows(activity.getActivities());
		      }
		    	
		      //if (index >=0 && index+1 < historicActivityInstanceList.size()) {
		      if (historicActivityInstanceList.contains(activity.getId())) {
		    	  //_log.info("* actId:" + activity.getId() + ", transitions: " + activity.getOutgoingTransitions());
		    	  List<PvmTransition> pvmTransitionList = activity.getOutgoingTransitions();
		    	  for (PvmTransition pvmTransition: pvmTransitionList) {
		    		  String destinationFlowId = pvmTransition.getDestination().getId();
		    		  //_log.info("- destinationFlowId: " + destinationFlowId + ", + " + historicActivityInstanceList.get(index+1));
		    		  if (historicActivityInstanceList.contains(destinationFlowId)) {
		    			  //_log.info("> actId:" + activity.getId() + ", flow: " + destinationFlowId);
		    			  highLightedFlows.add(pvmTransition.getId());
		    		  }
		    	  }
		      }
		    }
	}

	protected InputStream getWorkflowImage(Long companyId, String workflowName, Integer workflowVersion) {
		String defId = null;
		
		_log.debug("Generate image for workflow " + workflowName + " version " + workflowVersion);

		// get application context
		ApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(this.getServletContext());

		// get beans
		WorkflowDefinitionExtensionDao workflowDefinitionExtensionDao = (WorkflowDefinitionExtensionDao)applicationContext.getBean("workflowDefinitionExtensionDao");
		RepositoryService repositoryService = (RepositoryService)applicationContext.getBean(RepositoryService.class);
		_log.debug("Get app context and beans");
		
		// get workflow definition
		ProcessDefinition def = workflowDefinitionExtensionDao.find(companyId, workflowName, workflowVersion);
		
		_log.debug("WorkflowDefExt: " + def);
	
		if (def != null) {
			defId = def.getId();
		}
	
		_log.debug("Definition Id: " + defId);
	
		if (defId != null) {
			// get Process Definition Diagram 
			return repositoryService.getProcessDiagram(defId);
		}

		return null;
	}
	
	public void sendFile(
			HttpServletRequest portletRequest, HttpServletResponse mimeResponse,
			String fileName, byte[] bytes,
			String contentType)
		throws IOException {

		setHeaders(portletRequest, mimeResponse, fileName, contentType);

		write(mimeResponse, bytes, 0, 0);
	}

	protected void setHeaders(HttpServletRequest request, HttpServletResponse response,
			String fileName, String contentType) {

			if (_log.isDebugEnabled()) {
				_log.debug("Sending file of type " + contentType);
			}

			// LEP-2201

			if (Validator.isNotNull(contentType)) {
				response.setContentType(contentType);
			}

			/*
			response.setProperty(
				HttpHeaders.CACHE_CONTROL, HttpHeaders.CACHE_CONTROL_PRIVATE_VALUE);
			response.setProperty(
				HttpHeaders.PRAGMA, HttpHeaders.PRAGMA_NO_CACHE_VALUE);
			*/
			
			if (Validator.isNotNull(fileName)) {
				String contentDisposition =
					"attachment; filename=\"" + fileName + "\"";

				// If necessary for non-ASCII characters, encode based on RFC 2184.
				// However, not all browsers support RFC 2184. See LEP-3127.

				boolean ascii = true;

				for (int i = 0; i < fileName.length(); i++) {
					if (!Validator.isAscii(fileName.charAt(i))) {
						ascii = false;

						break;
					}
				}

				try {
					if (!ascii) {
						String encodedFileName = HttpUtil.encodeURL(fileName, true);

						if (BrowserSnifferUtil.isIe(request)) {
							contentDisposition =
								"attachment; filename=\"" + encodedFileName + "\"";
						}
						else {
							contentDisposition =
								"attachment; filename*=UTF-8''" + encodedFileName;
						}
					}
				}
				catch (Exception e) {
					if (_log.isWarnEnabled()) {
						_log.warn(e);
					}
				}

				String extension = GetterUtil.getString(
					FileUtil.getExtension(fileName)).toLowerCase();

				String[] mimeTypesContentDispositionInline = null;

				try {
					mimeTypesContentDispositionInline = PropsUtil.getArray(
						"mime.types.content.disposition.inline");
				}
				catch (Exception e) {
					mimeTypesContentDispositionInline = new String[0];
				}

				if (ArrayUtil.contains(
						mimeTypesContentDispositionInline, extension)) {

					contentDisposition = StringUtil.replace(
						contentDisposition, "attachment; ", "inline; ");
				}

				response.setHeader("Content-Disposition","inline; filename=" + fileName);
			}
		}

	public static void write(
			HttpServletResponse response, byte[] bytes, int offset,
			int contentLength)
		throws IOException {

		if (contentLength == 0) {
			contentLength = bytes.length;
		}


		response.setContentLength(contentLength);
		OutputStream outputStream = response.getOutputStream();

		outputStream.write(bytes, offset, contentLength);
	}

}
