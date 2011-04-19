package org.python.pydev.debug.ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.SelectionDialog;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.python.pydev.core.StringMatcher;
import org.python.pydev.debug.ui.actions.PyExceptionListProvider;

public class PyConfigureExceptionDialog extends SelectionDialog {

	protected DefaultFilterMatcher fFilterMatcher = new DefaultFilterMatcher();
	protected boolean updateInThread = true;

	// the visual selection widget group
	private Text filterPatternField;
	private Text addNewExceptionField;

	// providers for populating this dialog
	private ILabelProvider labelProvider;
	private IStructuredContentProvider contentProvider;
	private String filterPattern;

	// the root element to populate the viewer with
	private Object inputElement;

	private FilterJob filterJob;

	// the visual selection widget group
	CheckboxTableViewer listViewer;

	// sizing constants
	private final static int SIZING_SELECTION_WIDGET_HEIGHT = 250;
	private final static int SIZING_SELECTION_WIDGET_WIDTH = 300;

	protected static String SELECT_ALL_TITLE = WorkbenchMessages.SelectionDialog_selectLabel;
	protected static String DESELECT_ALL_TITLE = WorkbenchMessages.SelectionDialog_deselectLabel;

	public PyConfigureExceptionDialog(Shell parentShell, Object input,
			IStructuredContentProvider contentProvider,
			ILabelProvider labelProvider, String message) {
		super(parentShell);
		setTitle(WorkbenchMessages.ListSelection_title);
		this.inputElement = input;
		this.contentProvider = contentProvider;
		this.labelProvider = labelProvider;
		if (message != null) {
			setMessage(message);
		} else {
			setMessage(WorkbenchMessages.ListSelection_message);
		}
	}

	/**
	 * 
	 * @param composite
	 *            the parent composite
	 * @return the message label
	 */
	protected Label createMessageArea(Composite composite) {
		Label filterLabel = new Label(composite, SWT.NONE);
		filterLabel.setLayoutData(new GridData(GridData.BEGINNING,
				GridData.CENTER, false, false, 2, 1));
		filterLabel.setText("Enter a filter (* = any number of "
				+ "characters, ? = any single character)"
				+ "\nor an empty string for no filtering:");

		filterPatternField = new Text(composite, SWT.BORDER);
		filterPatternField.setLayoutData(new GridData(GridData.FILL,
				GridData.CENTER, true, false));

		return filterLabel;
	}

	/**
	 * Add the selection and deselection buttons to the dialog.
	 * 
	 * @param composite
	 *            org.eclipse.swt.widgets.Composite
	 */
	protected void addSelectionButtons(Composite composite) {
		Composite buttonComposite = new Composite(composite, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.numColumns = 0;
		layout.marginWidth = 0;
		layout.horizontalSpacing = convertHorizontalDLUsToPixels(IDialogConstants.HORIZONTAL_SPACING);
		buttonComposite.setLayout(layout);
		buttonComposite.setLayoutData(new GridData(SWT.END, SWT.TOP, true,
				false));

		Button selectButton = createButton(buttonComposite,
				IDialogConstants.SELECT_ALL_ID, SELECT_ALL_TITLE, false);

		SelectionListener listener = new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				listViewer.setAllChecked(true);
			}
		};
		selectButton.addSelectionListener(listener);

		Button deselectButton = createButton(buttonComposite,
				IDialogConstants.DESELECT_ALL_ID, DESELECT_ALL_TITLE, false);

		listener = new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				listViewer.setAllChecked(false);
			}
		};
		deselectButton.addSelectionListener(listener);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		// page group
		Composite composite = (Composite) super.createDialogArea(parent);

		initializeDialogUnits(composite);

		createMessageArea(composite);

		listViewer = CheckboxTableViewer.newCheckList(composite, SWT.BORDER);
		GridData data = new GridData(GridData.FILL_BOTH);
		data.heightHint = SIZING_SELECTION_WIDGET_HEIGHT;
		data.widthHint = SIZING_SELECTION_WIDGET_WIDTH;
		listViewer.getTable().setLayoutData(data);

		listViewer.setLabelProvider(labelProvider);
		listViewer.setContentProvider(contentProvider);

		addSelectionButtons(composite);

		initContent();
		// initialize page
		if (!getInitialElementSelections().isEmpty()) {
			checkInitialSelections();
		}

		Dialog.applyDialogFont(composite);

		getViewer().addFilter(new ViewerFilter() {
			public boolean select(Viewer viewer, Object parentElement,
					Object element) {
				if (getCheckBoxTableViewer().getChecked(element)) {
					addToSelectedElements(element);
				}
				return matchExceptionToShowInList(element);
			}
		});

		getCheckBoxTableViewer().addCheckStateListener(
				new ICheckStateListener() {
					public void checkStateChanged(CheckStateChangedEvent event) {
						if (event.getChecked()) {
							addToSelectedElements(event.getElement());
						} else {
							removeFromSelectedElements(event.getElement());
						}
					}
				});

		addNewExceptionField = new Text(composite, SWT.BORDER);
		addNewExceptionField.setLayoutData(new GridData(GridData.FILL,
				GridData.BEGINNING, true, false));

		customExceptionUI(composite);

		return composite;
	}

	/**
	 * @param composite
	 * 
	 *            Create a new text box and a button, which allows user to add
	 *            custom exception. Attach a listener to the AddException Button
	 */
	private void customExceptionUI(Composite composite) {
		Button buttonAdd = new Button(composite, SWT.PUSH);
		buttonAdd.setLayoutData(new GridData(GridData.END, GridData.END, true,
				false));
		buttonAdd.setText("Add Exception");

		SelectionListener listener = new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				addCustomException();
			}
		};
		buttonAdd.addSelectionListener(listener);
	}

	/**
	 * Add the new exception in the content pane
	 * 
	 */
	private void addCustomException() {
		Object customException = addNewExceptionField.getText();
		Object[] currentElements = contentProvider.getElements(inputElement);

		ArrayList<Object> currentElementsList = new ArrayList<Object>();
		for (int i = 0; i < currentElements.length; ++i) {
			Object element = currentElements[i];
			currentElementsList.add(element);
		}
		
		if(customException == "")
			return;
		
		if (!currentElementsList.contains(customException)) {
			getViewer().add(customException);
			addNewExceptionField.setText("");
			((PyExceptionListProvider) contentProvider)
					.addUserConfiguredException(customException);
		} else {
			IStatus status = new Status(IStatus.WARNING,
					DebugUIPlugin.getUniqueIdentifier(),
					"Duplicate: This exception already exists");
			DebugUIPlugin.errorDialog(getShell(), DebugUIPlugin
					.removeAccelerators("Add Custom User Exception"), "Error",
					status);
		}
	}

	/**
	 * Updates the current filter with the text field text.
	 */
	protected void doFilterUpdate(IProgressMonitor monitor) {
		setFilter(filterPatternField.getText(), monitor, true);
	}

	// filtering things...
	protected void setFilter(String text, IProgressMonitor monitor,
			boolean updateFilterMatcher) {
		if (monitor.isCanceled())
			return;

		if (updateFilterMatcher) {
			// just so that subclasses may already treat it.
			if (fFilterMatcher.lastPattern.equals(text)) {
				// no actual change...
				return;
			}
			fFilterMatcher.setFilter(text);
			if (monitor.isCanceled())
				return;
		}

		getViewer().refresh();
		setSelectedElementChecked();
	}

	protected boolean matchExceptionToShowInList(Object element) {
		return fFilterMatcher.match(element);
	}

	/**
	 * The <code>ListSelectionDialog</code> implementation of this
	 * <code>Dialog</code> method builds a list of the selected elements for
	 * later retrieval by the client and closes this dialog.
	 */
	protected void okPressed() {

		// Get the input children.
		Object[] children = contentProvider.getElements(inputElement);
		// Build a list of selected children.
		if (children != null) {
			ArrayList list = new ArrayList();
			for (int i = 0; i < children.length; ++i) {
				Object element = children[i];
				if (listViewer.getChecked(element)) {
					list.add(element);
				}
			}
			// If filter is on and checkedElements are not in filtered list
			// then content provider.getElements doesn't fetch the same
			List<Object> selectedElements = getSelectedElements();
			for (Object selectedElement : selectedElements) {
				if(!list.contains(selectedElement)){
					list.add(selectedElement);
				}
			}
			setResult(list);
		}

		super.okPressed();
	}

	/**
	 * Returns the viewer used to show the list.
	 * 
	 * @return the viewer, or <code>null</code> if not yet created
	 */
	protected CheckboxTableViewer getViewer() {
		return listViewer;
	}

	/**
	 * Returns the viewer cast to the correct instance. Possibly
	 * <code>null</code> if the viewer has not been created yet.
	 * 
	 * @return the viewer cast to CheckboxTableViewer
	 */
	protected CheckboxTableViewer getCheckBoxTableViewer() {
		return (CheckboxTableViewer) getViewer();
	}

	/**
	 * Initialises this dialog's viewer after it has been laid out.
	 */
	private void initContent() {
		listViewer.setInput(inputElement);
		Listener listener = new Listener() {
			public void handleEvent(Event e) {
				if (updateInThread) {
					if (filterJob != null) {
						// cancel it if it was already in progress
						filterJob.cancel();
					}
					filterJob = new FilterJob();
					filterJob.start();
				} else {
					doFilterUpdate(new NullProgressMonitor());
				}
			}
		};

		filterPatternField.setText(filterPattern != null ? filterPattern : "");
		filterPatternField.addListener(SWT.Modify, listener);
	}

	/**
	 * Visually checks the previously-specified elements in this dialog's list
	 * viewer.
	 */
	private void checkInitialSelections() {
		Iterator itemsToCheck = getInitialElementSelections().iterator();

		while (itemsToCheck.hasNext()) {
			listViewer.setChecked(itemsToCheck.next(), true);
		}
	}

	/**
	 * setSelectedElementChecked
	 * 
	 * Visually checks the elements in the selectedElements list after the
	 * refresh, which is triggered on applying / removing filter
	 * 
	 */
	private void setSelectedElementChecked() {
		for (Object element : getSelectedElements()) {
			getViewer().setChecked(element, true);
		}
	}

	private List<Object> selectedElements;

	private List<Object> getSelectedElements() {
		return selectedElements;
	}

	private void addToSelectedElements(Object element) {
		if (selectedElements == null)
			selectedElements = new ArrayList<Object>();
		if (!selectedElements.contains(element))
			selectedElements.add(element);
	}

	private void removeFromSelectedElements(Object element) {
		if (selectedElements != null && selectedElements.contains(element))
			selectedElements.remove(element);
	}

	class FilterJob extends Thread {
		// only thing it implements is the cancelled
		IProgressMonitor monitor = new NullProgressMonitor();

		public FilterJob() {
			setPriority(Thread.MIN_PRIORITY);
			setName("PyConfigureExceptionDialog: FilterJob");
		}

		@Override
		public void run() {
			try {
				sleep(300);
			} catch (InterruptedException e) {
				// ignore
			}
			if (!monitor.isCanceled()) {
				Display display = Display.getDefault();
				display.asyncExec(new Runnable() {

					public void run() {
						if (!monitor.isCanceled()) {
							doFilterUpdate(monitor);
						}
					}

				});
			}
		}

		public void cancel() {
			this.monitor.setCanceled(true);
		}
	}

	protected class DefaultFilterMatcher {
		public StringMatcher fMatcher;
		public String lastPattern;

		public DefaultFilterMatcher() {
			setFilter("");

		}

		public void setFilter(String pattern) {
			setFilter(pattern, true, false);
		}

		private void setFilter(String pattern, boolean ignoreCase,
				boolean ignoreWildCards) {
			fMatcher = new StringMatcher(pattern + '*', ignoreCase,
					ignoreWildCards);
			this.lastPattern = pattern;
		}

		public boolean match(Object element) {
			boolean match = fMatcher.match(labelProvider.getText(element));
			if (match) {
				return true;
			}
			return false;
		}
	}
}
