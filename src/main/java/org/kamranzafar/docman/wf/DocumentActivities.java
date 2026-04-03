package org.kamranzafar.docman.wf;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.kamranzafar.docman.model.Document;

@ActivityInterface
public interface DocumentActivities {
    @ActivityMethod
    Document create(Document document);
    @ActivityMethod
    Document update(Document document);
    String extract(String documentId);
    @ActivityMethod
    Document index(String documentId, String content);
    @ActivityMethod
    void notify(String documentId, String msg);
}
