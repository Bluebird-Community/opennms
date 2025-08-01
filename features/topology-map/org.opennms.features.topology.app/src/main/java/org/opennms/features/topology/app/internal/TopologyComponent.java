/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.features.topology.app.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.opennms.features.topology.api.BoundingBox;
import org.opennms.features.topology.api.Graph;
import org.opennms.features.topology.api.GraphContainer;
import org.opennms.features.topology.api.GraphContainer.ChangeListener;
import org.opennms.features.topology.api.GraphVisitor;
import org.opennms.features.topology.api.MapViewManager;
import org.opennms.features.topology.api.MapViewManagerListener;
import org.opennms.features.topology.api.Point;
import org.opennms.features.topology.api.SelectionContext;
import org.opennms.features.topology.api.SelectionListener;
import org.opennms.features.topology.api.topo.Edge;
import org.opennms.features.topology.api.topo.Vertex;
import org.opennms.features.topology.api.topo.VertexRef;
import org.opennms.features.topology.app.internal.gwt.client.TopologyComponentServerRpc;
import org.opennms.features.topology.app.internal.gwt.client.TopologyComponentState;
import org.opennms.features.topology.app.internal.menu.MenuUpdateListener;
import org.opennms.features.topology.app.internal.support.IconRepositoryManager;
import org.slf4j.LoggerFactory;

import com.vaadin.annotations.JavaScript;
import com.vaadin.annotations.StyleSheet;
import com.vaadin.v7.data.Property;
import com.vaadin.v7.data.Property.ValueChangeEvent;
import com.vaadin.v7.data.Property.ValueChangeListener;
import com.vaadin.shared.MouseEventDetails;
import com.vaadin.ui.AbstractComponent;

@JavaScript({
    "theme://../opennms/assets/topology-init.vaadin.js"
})
@StyleSheet({
    "theme://../opennms/assets/leaflet.css"
})
public class TopologyComponent extends AbstractComponent implements ChangeListener, ValueChangeListener, MapViewManagerListener {

    TopologyComponentServerRpc m_rpc = new TopologyComponentServerRpc(){

        private static final long serialVersionUID = 6945103738578953304L;

        @Override
        public void doubleClicked(MouseEventDetails eventDetails) {
            double scale = getViewManager().getScale() + 0.25;
            getViewManager().zoomToPoint(scale, new Point(eventDetails.getClientX(), eventDetails.getClientY()));
        }

        @Override
        public void deselectAllItems() {
            m_graphContainer.getSelectionManager().deselectAll();
        }

        @Override
        public void edgeClicked(String edgeId) {
            selectEdge(edgeId);
        }

        @Override
        public void backgroundClicked() {
            m_blockSelectionEvents = true;
            m_graphContainer.getSelectionManager().deselectAll();
            updateMenuItems();
            m_blockSelectionEvents = false;
        }

        @Override
        public void scrollWheel(double scrollVal, int x, int y) {
            getViewManager().zoomToPoint(getViewManager().getScale() - scrollVal, new Point(x, y));
        }

        @Override
        public void mapPhysicalBounds(int width, int height) {
            getState().setPhysicalDimensions(width, height);
            getViewManager().setViewPort(width, height);
            getViewManager().setMapBounds(m_graph.getLayout().getBounds());
        }

        @Override
        public void marqueeSelection(String[] vertexKeys, MouseEventDetails eventDetails) {
            selectVertices(eventDetails.isShiftKey(), eventDetails.isCtrlKey(), vertexKeys);
        }

        @Override
        public void contextMenu(String target, String type, int x, int y) {

            Object menuTarget = null;
            if (type.toLowerCase().equals("vertex")) {
                menuTarget = getGraph().getVertexByKey(target);
            } else if (type.toLowerCase().equals("edge")) {
                menuTarget = getGraph().getEdgeByKey(target);
            }

            m_contextMenuHandler.showContextMenu(menuTarget, x, y);

        }

        @Override
        public void clientCenterPoint(int x, int y) {
            getViewManager().setCenter(new Point(x, y));
        }

        @Override
        public void vertexClicked(String vertexId, MouseEventDetails eventDetails, String platform) {
            selectVertices(eventDetails.isShiftKey(), eventDetails.isCtrlKey(), vertexId);
        }

        @Override
        public void updateVertices(List<String> vertices) {
            for(String vUpdate : vertices) {
                updateVertex(vUpdate);
            }

            fireVertexUpdated();
            if(vertices.size() > 0) {
                updateGraph();
            }
        }

        @Override
        public void backgroundDoubleClick(double x, double y) {
            //TODO: set the center point and zoom in by 25%
        }

    };

    public interface VertexUpdateListener{
        void onVertexUpdate();
    }

    private static final long serialVersionUID = 1L;

    private final GraphContainer m_graphContainer;
    private Graph m_graph;
    private final List<MenuUpdateListener> m_menuItemStateListener = new ArrayList<>();
    private final ContextMenuHandler m_contextMenuHandler;
    private final IconRepositoryManager m_iconRepoManager;
    private String m_activeTool = "pan";
    private boolean m_blockSelectionEvents = false;

    private Set<VertexUpdateListener> m_vertexUpdateListeners = new CopyOnWriteArraySet<>();

    public TopologyComponent(GraphContainer dataSource, IconRepositoryManager iconRepositoryManager, ContextMenuHandler contextMenuHandler) {
        m_graphContainer = dataSource;
        m_iconRepoManager = iconRepositoryManager;
        m_contextMenuHandler = contextMenuHandler;

        registerRpc(m_rpc);

        setGraph(m_graphContainer.getGraph());

        m_graphContainer.getSelectionManager().addSelectionListener(new SelectionListener() {

            @Override
            public void selectionChanged(SelectionContext selectionContext) {
                if (!m_blockSelectionEvents) {
                    computeBoundsForSelected(selectionContext);
                }
                updateGraph();
            }
        });

        m_graphContainer.getMapViewManager().addListener(this);
        m_graphContainer.addChangeListener(this);

        setScaleDataSource(m_graphContainer.getScaleProperty());

        getState().setSVGDefFiles(m_iconRepoManager.getSVGIconFiles());
        updateGraph();
    }

    private void setScaleDataSource(Property<Double> scale) {
        // Listens the new data source if possible
        if (scale != null
                && Property.ValueChangeNotifier.class
                .isAssignableFrom(scale.getClass())) {
            ((Property.ValueChangeNotifier) scale).addValueChangeListener(this);
        }
    }

    @Override
    protected TopologyComponentState getState() {
        return (TopologyComponentState) super.getState();
    }

    public void updateGraph() {
        BoundingBox boundingBox = getBoundingBox();
        getState().setBoundX(boundingBox.getX());
        getState().setBoundY(boundingBox.getY());
        getState().setBoundWidth(boundingBox.getWidth());
        getState().setBoundHeight(boundingBox.getHeight());
        getState().setActiveTool(m_activeTool);

        Graph graph = getGraph();
        GraphVisitor painter = new GraphPainter(m_graphContainer, graph.getLayout(), m_iconRepoManager, getState());
        try {
            graph.visit(painter);
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass()).error(e.getMessage(), e);
        }
    }

    private BoundingBox getBoundingBox() {

        return getViewManager().getCurrentBoundingBox();
    }

    private void selectVertices(boolean shiftModifierPressed, boolean ctrlModifierPressed, String... vertexKeys) {
        m_blockSelectionEvents = true;
        List<VertexRef> vertexRefsToSelect = new ArrayList<VertexRef>(vertexKeys.length);
        List<VertexRef> vertexRefsToDeselect = new ArrayList<>();
        boolean add = shiftModifierPressed || ctrlModifierPressed;
        for (String eachVertexKey : vertexKeys) {
            if (ctrlModifierPressed
                    && m_graphContainer.getSelectionManager().isVertexRefSelected(m_graph.getVertexByKey(eachVertexKey))) {
                vertexRefsToDeselect.add(getGraph().getVertexByKey(eachVertexKey)); //we want it to be unselected
            } else {
                vertexRefsToSelect.add(getGraph().getVertexByKey(eachVertexKey));
            }
        }
        if (add) { // we want to add, so add the already selected ones (except the explicit removed ones)
            vertexRefsToSelect.addAll(m_graphContainer.getSelectionManager().getSelectedVertexRefs());
            vertexRefsToSelect.removeAll(vertexRefsToDeselect);
        }
        Collection<VertexRef> selectedVertices = m_graphContainer.getVertexRefForest(vertexRefsToSelect);
        if (!m_graphContainer.getSelectionManager().getSelectedVertexRefs().equals(selectedVertices)) {
            m_graphContainer.getSelectionManager().deselectAll();
            m_graphContainer.getSelectionManager().selectVertexRefs(selectedVertices);
            m_blockSelectionEvents = false;
            updateMenuItems();
        }
    }

    private void selectEdge(String edgeKey) {
        Edge edge = getGraph().getEdgeByKey(edgeKey);

        m_graphContainer.getSelectionManager().setSelectedEdgeRefs(Collections.singleton(edge));

    }

    private void updateVertex(String vertexUpdate) {
        String[] vertexProps = vertexUpdate.split("\\|");

        String id = vertexProps[0].split(",")[1];
        int x = (int) Double.parseDouble(vertexProps[1].split(",")[1]);
        int y = (int) Double.parseDouble(vertexProps[2].split(",")[1]);
        boolean selected = vertexProps[3].split(",")[1].equals("true");

        Vertex vertex = getGraph().getVertexByKey(id);

        getGraph().getLayout().setLocation(vertex, new Point(x, y));

        if (selected) {
            m_graphContainer.getSelectionManager().selectVertexRefs(Collections.singleton(vertex));
        } else {
            m_graphContainer.getSelectionManager().deselectVertexRefs(Collections.singleton(vertex));
        }
    }

    protected void setScale(double scale){
        m_graphContainer.setScale(scale);
    }

    protected Graph getGraph() {
        return m_graph;
    }

    private void setGraph(Graph graph) {
        m_graph = graph;
        getViewManager().setMapBounds(graph.getLayout().getBounds());
    }

    public void addMenuItemStateListener(MenuUpdateListener listener) {
        m_menuItemStateListener.add(listener);
    }

    public void removeMenuItemStateListener(MenuUpdateListener listener) {
        m_menuItemStateListener.remove(listener);
    }

    private void updateMenuItems() {
        for(MenuUpdateListener listener : m_menuItemStateListener) {
            listener.updateMenu();
        }
    }

    @Override
    public void graphChanged(GraphContainer container) {
        Graph graph = container.getGraph();
        setGraph(graph);

        if(!m_blockSelectionEvents){
            computeBoundsForSelected(m_graphContainer.getSelectionManager());
        }
        updateGraph();
    }

    /**
     * ValueChange listener for the scale property
     */
    @Override
    public void valueChange(ValueChangeEvent event) {

        double scale = (Double) event.getProperty().getValue();

        getViewManager().setScale(scale);

    }

    public void setActiveTool(String toolname) {
        if(!m_activeTool.equals(toolname)) {
            m_activeTool = toolname;
            getState().setActiveTool(toolname);
            updateGraph();
        }
    }

    public void showAllMap(){
        getViewManager().setBoundingBox(m_graphContainer.getGraph().getLayout().getBounds());
    }

    public void centerMapOnSelection(){
        computeBoundsForSelected(m_graphContainer.getSelectionManager());
    }

    private void computeBoundsForSelected(SelectionContext selectionContext) {
        if(selectionContext.getSelectedVertexRefs().size() > 0) {
            Collection<? extends Vertex> visible = m_graphContainer.getGraph().getDisplayVertices();
            Collection<VertexRef> selected = selectionContext.getSelectedVertexRefs();
            Collection<VertexRef> vRefs = new ArrayList<>();
            for(VertexRef vRef : selected) {
                if(visible.contains(vRef)) {
                    vRefs.add(vRef);
                }
            }

            getViewManager().setBoundingBox(m_graphContainer.getGraph().getLayout().computeBoundingBox(vRefs));

        } else {
            getViewManager().setBoundingBox(m_graphContainer.getGraph().getLayout().getBounds());
        }
    }

    @Override
    public void boundingBoxChanged(MapViewManager viewManager) {
        setScale(viewManager.getScale());
        updateGraph();
    }

    public MapViewManager getViewManager() {
        return m_graphContainer.getMapViewManager();
    }

    public void addVertexUpdateListener(VertexUpdateListener listener) {
        m_vertexUpdateListeners.add(listener);
    }

    private void fireVertexUpdated() {
        for(VertexUpdateListener listener : m_vertexUpdateListeners) {
            listener.onVertexUpdate();
        }
    }

    public void blockSelectionEvents(){
        m_blockSelectionEvents = true;
    }

    public void unblockSelectionEvents(){
        m_blockSelectionEvents = false;
    }

}
