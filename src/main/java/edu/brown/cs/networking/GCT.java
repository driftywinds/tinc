package edu.brown.cs.networking;

import static edu.brown.cs.networking.Util.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import spark.Spark;

/**
 * Grand Central Terminal - Routes all of the inputs to appropriate groups
 *
 * @author ndemarco
 */
public final class GCT {

  private final Set<Group>       pending;
  private final Set<Group>       full;
  private final Map<User, Group> userToUserGroup;
  private final GroupSelector    groupSelector;

  private static final int       GAME_LIMIT = 20;


  private GCT(GCTBuilder builder) {
    // ConcurrentHashMap.newKeySet() is the standard-library replacement for
    // the removed Jetty-internal org.eclipse.jetty.util.ConcurrentHashSet.
    this.pending         = ConcurrentHashMap.newKeySet();
    this.full            = ConcurrentHashMap.newKeySet();
    this.userToUserGroup = new ConcurrentHashMap<>();

    this.groupSelector = builder.groupSelector;
    Spark.webSocket(builder.webSocketRoute, ReceivingWebsocket.class);
    ReceivingWebsocket.setGct(this);

    if (builder.groupViewRoute != null) {
      Spark.webSocket(builder.groupViewRoute, GroupViewWebsocket.class);
      GroupViewWebsocket.setGCT(this);
    }
    Spark.init();
  }


  public int groupLimit() {
    return GAME_LIMIT;
  }


  public Group groupForUser(User u) {
    return userToUserGroup.get(u);
  }


  public boolean userIDIsValid(String uuid) {
    return pending.stream().anyMatch(grp -> grp.hasUser(uuid))
        || full.stream().anyMatch(grp -> grp.hasUser(uuid));
  }


  public JsonObject openGroups() {
    refreshGroups();
    Collection<Group> list = new ArrayList<>();
    pending.stream().filter(g -> !g.isEmpty())
        .forEach(g -> list.add(new GroupView(g)));
    Collection<Group> gr = Collections.unmodifiableCollection(list);
    JsonObject toRet = new JsonObject();
    toRet.add("groups", Networking.GSON.toJsonTree(gr));
    boolean atLim = pending.size() + full.size() >= GAME_LIMIT;
    toRet.addProperty("atLimit", atLim);
    return toRet;
  }


  public JsonObject closedGroups() {
    refreshGroups();
    Collection<Group> list = new ArrayList<>();
    full.forEach(g -> list.add(new GroupView(g)));
    Collection<Group> gr = Collections.unmodifiableCollection(list);
    JsonObject toRet = new JsonObject();
    toRet.add("closedGroups", Networking.GSON.toJsonTree(gr));
    return toRet;
  }


  private void refreshGroups() {
    for (Group g : full) {
      if (g.isEmpty()) {
        full.remove(g);
      } else if (!g.isFull()) {
        full.remove(g);
        pending.add(g);
      }
    }
    for (Group g : pending) {
      if (g.isEmpty()) {
        pending.remove(g);
      } else if (g.isFull()) {
        pending.remove(g);
        full.add(g);
      }
    }
  }


  public boolean add(User u) {
    Group bestFit =
        groupSelector.selectFor(u, Collections.unmodifiableCollection(pending));
    if (bestFit == null) {
      return false;
    }

    userToUserGroup.put(u, bestFit);
    bestFit.add(u);

    format("User %s added to %s%n", u, bestFit);
    filterGroup(bestFit);
    return true;
  }


  public boolean remove(User u) {
    Group group = userToUserGroup.get(u);
    if (group == null) {
      return false;
    }
    group.remove(u);
    userToUserGroup.remove(u);
    filterGroup(group);
    return true;
  }


  public boolean message(User u, JsonObject j) {
    Group group = userToUserGroup.get(u);
    if (group == null) {
      return false;
    }
    return group.handleMessage(u, j);
  }


  private void filterGroup(Group g) {
    if (g.isFull()) {
      full.add(g);
      pending.remove(g);
    } else if (g.isEmpty()) {
      pending.remove(g);
      full.remove(g);
    } else {
      pending.add(g);
      full.remove(g);
    }
    GroupViewWebsocket.reportChange(openGroups());
  }


  public static class GCTBuilder {

    private final String  webSocketRoute;
    private String        groupViewRoute;
    private GroupSelector groupSelector = new BasicGroupSelector();


    public GCTBuilder(String route) {
      this.webSocketRoute = route;
    }


    public GCTBuilder withGroupViewRoute(String route) {
      this.groupViewRoute = route;
      return this;
    }


    public GCTBuilder withGroupSelector(GroupSelector selector) {
      this.groupSelector = selector;
      return this;
    }


    public GCT build() {
      return new GCT(this);
    }

  }

}