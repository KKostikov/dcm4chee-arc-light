/*
 * **** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2019
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4chee.arc.diff.impl;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.hibernate.HibernateQuery;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.diff.*;
import org.dcm4chee.arc.entity.*;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.qmgt.IllegalTaskStateException;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.dcm4chee.arc.query.util.MatchTask;
import org.dcm4chee.arc.query.util.TaskQueryParam;
import org.hibernate.Session;
import org.hibernate.annotations.QueryHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Mar 2018
 */
@Stateless
public class DiffServiceEJB {

    static final Logger LOG = LoggerFactory.getLogger(DiffServiceEJB.class);

    @PersistenceContext(unitName="dcm4chee-arc")
    private EntityManager em;

    private Join<DiffTask, QueueMessage> queueMsg;
    private Root<DiffTask> diffTask;

    @Inject
    private Device device;

    @Inject
    private QueueManager queueManager;

    private static final Expression<?>[] SELECT = {
            QQueueMessage.queueMessage.processingStartTime.min(),
            QQueueMessage.queueMessage.processingStartTime.max(),
            QQueueMessage.queueMessage.processingEndTime.min(),
            QQueueMessage.queueMessage.processingEndTime.max(),
            QQueueMessage.queueMessage.scheduledTime.min(),
            QQueueMessage.queueMessage.scheduledTime.max(),
            QDiffTask.diffTask.createdTime.min(),
            QDiffTask.diffTask.createdTime.max(),
            QDiffTask.diffTask.updatedTime.min(),
            QDiffTask.diffTask.updatedTime.max(),
            QDiffTask.diffTask.matches.sum(),
            QDiffTask.diffTask.missing.sum(),
            QDiffTask.diffTask.different.sum(),
            QQueueMessage.queueMessage.batchID
    };

    public void scheduleDiffTask(DiffContext ctx) throws QueueSizeLimitExceededException {
        try {
            ObjectMessage msg = queueManager.createObjectMessage(0);
            msg.setStringProperty("LocalAET", ctx.getLocalAE().getAETitle());
            msg.setStringProperty("PrimaryAET", ctx.getPrimaryAE().getAETitle());
            msg.setStringProperty("SecondaryAET", ctx.getSecondaryAE().getAETitle());
            msg.setIntProperty("Priority", ctx.priority());
            msg.setStringProperty("QueryString", ctx.getQueryString());
            if (ctx.getHttpServletRequestInfo() != null)
                ctx.getHttpServletRequestInfo().copyTo(msg);
            QueueMessage queueMessage = queueManager.scheduleMessage(DiffService.QUEUE_NAME, msg,
                    Message.DEFAULT_PRIORITY, ctx.getBatchID(), 0L);
            createDiffTask(ctx, queueMessage);
        } catch (JMSException e) {
            throw QueueMessage.toJMSRuntimeException(e);
        }
    }

    private void createDiffTask(DiffContext ctx, QueueMessage queueMessage) {
        DiffTask task = new DiffTask();
        task.setLocalAET(ctx.getLocalAE().getAETitle());
        task.setPrimaryAET(ctx.getPrimaryAE().getAETitle());
        task.setSecondaryAET(ctx.getSecondaryAE().getAETitle());
        task.setQueryString(ctx.getQueryString());
        task.setCheckMissing(ctx.isCheckMissing());
        task.setCheckDifferent(ctx.isCheckDifferent());
        task.setCompareFields(ctx.getCompareFields());
        task.setQueueMessage(queueMessage);
        em.persist(task);
    }

    public void resetDiffTask(DiffTask diffTask) {
        diffTask = em.find(DiffTask.class, diffTask.getPk());
        diffTask.reset();
        diffTask.getDiffTaskAttributes().forEach(entity -> em.remove(entity));
    }

    public void addDiffTaskAttributes(DiffTask diffTask, Attributes attrs) {
        DiffTaskAttributes entity = new DiffTaskAttributes();
        entity.setDiffTask(diffTask);
        entity.setAttributes(attrs);
        em.persist(entity);
    }

    public void updateDiffTask(DiffTask diffTask, DiffSCU diffSCU) {
        diffTask = em.find(DiffTask.class, diffTask.getPk());
        if (diffTask != null) {
            diffTask.setMatches(diffSCU.matches());
            diffTask.setMissing(diffSCU.missing());
            diffTask.setDifferent(diffSCU.different());
        }
    }

    private HibernateQuery<DiffTask> createQuery(Predicate matchQueueMessage, Predicate matchDiffTask) {
        HibernateQuery<QueueMessage> queueMsgQuery = new HibernateQuery<QueueMessage>(em.unwrap(Session.class))
                .from(QQueueMessage.queueMessage)
                .where(matchQueueMessage);
        return new HibernateQuery<DiffTask>(em.unwrap(Session.class))
                .from(QDiffTask.diffTask)
                .where(matchDiffTask, QDiffTask.diffTask.queueMessage.in(queueMsgQuery));
    }

    public DiffTask getDiffTask(long taskPK) {
        return em.find(DiffTask.class, taskPK);
    }

    public boolean deleteDiffTask(Long pk, QueueMessageEvent queueEvent) {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return false;

        queueManager.deleteTask(task.getQueueMessage().getMessageID(), queueEvent);
        LOG.info("Delete {}", task);
        return true;
    }

    public int deleteTasks(Predicate matchQueueMessage, Predicate matchDiffTask, int deleteTasksFetchSize) {
        List<String> referencedQueueMsgIDs = createQuery(matchQueueMessage, matchDiffTask)
                    .select(QDiffTask.diffTask.queueMessage.messageID)
                    .limit(deleteTasksFetchSize)
                    .fetch();

        for (String queueMsgID : referencedQueueMsgIDs)
            queueManager.deleteTask(queueMsgID, null);

        return referencedQueueMsgIDs.size();
    }

    public List<String> listDistinctDeviceNames(Predicate matchQueueMessage, Predicate matchDiffTask) {
        return createQuery(matchQueueMessage, matchDiffTask)
                .select(QQueueMessage.queueMessage.deviceName)
                .distinct()
                .fetch();
    }

    public long diffTasksOfBatch(String batchID) {
        return batchIDQuery(batchID).fetchCount();
    }

    public boolean cancelDiffTask(Long pk, QueueMessageEvent queueEvent) throws IllegalTaskStateException {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return false;

        QueueMessage queueMessage = task.getQueueMessage();
        if (queueMessage == null)
            throw new IllegalTaskStateException("Cannot cancel Task with status: 'TO SCHEDULE'");

        queueManager.cancelTask(queueMessage.getMessageID(), queueEvent);
        LOG.info("Cancel {}", task);
        return true;
    }

    public long cancelDiffTasks(Predicate matchQueueMessage, Predicate matchDiffTask, QueueMessage.Status prev)
            throws IllegalTaskStateException {
        return queueManager.cancelDiffTasks(matchQueueMessage, matchDiffTask, prev);
    }

    public void rescheduleDiffTask(Long pk, QueueMessageEvent queueEvent) {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return;

        LOG.info("Reschedule {}", task);
        rescheduleDiffTask(task.getQueueMessage().getMessageID(), queueEvent);
    }

    public void rescheduleDiffTask(String msgId, QueueMessageEvent queueEvent) {
        queueManager.rescheduleTask(msgId, DiffService.QUEUE_NAME, queueEvent);
    }

    public List<String> listDiffTaskQueueMsgIDs(Predicate matchQueueMsg, Predicate matchDiffTask, int limit) {
        return createQuery(matchQueueMsg, matchDiffTask)
                .select(QQueueMessage.queueMessage.messageID)
                .limit(limit)
                .fetch();
    }

    public String findDeviceNameByPk(Long pk) {
        try {
            return em.createNamedQuery(DiffTask.FIND_DEVICE_BY_PK, String.class)
                    .setParameter(1, pk)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public List<AttributesBlob> getDiffTaskAttributes(DiffTask diffTask, int offset, int limit) {
        return em.createNamedQuery(DiffTaskAttributes.FIND_BY_DIFF_TASK, AttributesBlob.class)
                .setParameter(1, diffTask)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public List<AttributesBlob> getDiffTaskAttributes(Predicate matchQueueBatch, Predicate matchDiffBatch, int offset, int limit) {
        HibernateQuery<DiffTask> diffTaskQuery = createQuery(matchQueueBatch, matchDiffBatch);
        if (limit > 0)
            diffTaskQuery.limit(limit);
        if (offset > 0)
            diffTaskQuery.offset(offset);

        return new HibernateQuery<DiffTaskAttributes>(em.unwrap(Session.class))
                .select(QDiffTaskAttributes.diffTaskAttributes.attributesBlob)
                .from(QDiffTaskAttributes.diffTaskAttributes)
                .where(QDiffTaskAttributes.diffTaskAttributes.diffTask.in(diffTaskQuery))
                .fetch();
    }

    public List<DiffBatch> listDiffBatches(Predicate matchQueueBatch, Predicate matchDiffBatch, OrderSpecifier<Date> order,
                                           int offset, int limit) {
        HibernateQuery<DiffTask> diffTaskQuery = createQuery(matchQueueBatch, matchDiffBatch);
        if (limit > 0)
            diffTaskQuery.limit(limit);
        if (offset > 0)
            diffTaskQuery.offset(offset);

        List<Tuple> batches = diffTaskQuery.select(SELECT)
                                .groupBy(QQueueMessage.queueMessage.batchID)
                                .orderBy(order)
                                .fetch();

        List<DiffBatch> diffBatches = new ArrayList<>();
        for (Tuple batch : batches) {
            DiffBatch diffBatch = new DiffBatch();
            String batchID = batch.get(QQueueMessage.queueMessage.batchID);
            diffBatch.setBatchID(batchID);

            diffBatch.setCreatedTimeRange(
                    batch.get(QDiffTask.diffTask.createdTime.min()),
                    batch.get(QDiffTask.diffTask.createdTime.max()));
            diffBatch.setUpdatedTimeRange(
                    batch.get(QDiffTask.diffTask.updatedTime.min()),
                    batch.get(QDiffTask.diffTask.updatedTime.max()));
            diffBatch.setScheduledTimeRange(
                    batch.get(QQueueMessage.queueMessage.scheduledTime.min()),
                    batch.get(QQueueMessage.queueMessage.scheduledTime.max()));
            diffBatch.setProcessingStartTimeRange(
                    batch.get(QQueueMessage.queueMessage.processingStartTime.min()),
                    batch.get(QQueueMessage.queueMessage.processingStartTime.max()));
            diffBatch.setProcessingEndTimeRange(
                    batch.get(QQueueMessage.queueMessage.processingEndTime.min()),
                    batch.get(QQueueMessage.queueMessage.processingEndTime.max()));
            diffBatch.setMatches(batch.get(QDiffTask.diffTask.matches.sum()));
            diffBatch.setMissing(batch.get(QDiffTask.diffTask.missing.sum()));
            diffBatch.setDifferent(batch.get(QDiffTask.diffTask.different.sum()));

            diffBatch.setDeviceNames(
                    batchIDQuery(batchID)
                        .select(QQueueMessage.queueMessage.deviceName)
                        .distinct()
                        .orderBy(QQueueMessage.queueMessage.deviceName.asc())
                        .fetch());
            diffBatch.setLocalAETs(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.localAET)
                        .distinct()
                        .orderBy(QDiffTask.diffTask.localAET.asc())
                        .fetch());
            diffBatch.setPrimaryAETs(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.primaryAET)
                        .distinct()
                        .orderBy(QDiffTask.diffTask.primaryAET.asc())
                        .fetch());
            diffBatch.setSecondaryAETs(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.secondaryAET)
                        .distinct()
                        .orderBy(QDiffTask.diffTask.secondaryAET.asc())
                        .fetch());
            diffBatch.setComparefields(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.compareFields)
                        .where(QDiffTask.diffTask.compareFields.isNotNull())
                        .distinct()
                        .fetch());
            diffBatch.setCheckMissing(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.checkMissing)
                        .distinct()
                        .fetch());
            diffBatch.setCheckDifferent(
                    batchIDQuery(batchID)
                        .select(QDiffTask.diffTask.checkDifferent)
                        .distinct()
                        .fetch());

            diffBatch.setCompleted(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.COMPLETED))
                        .fetchCount());
            diffBatch.setCanceled(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.CANCELED))
                        .fetchCount());
            diffBatch.setWarning(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.WARNING))
                        .fetchCount());
            diffBatch.setFailed(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.FAILED))
                        .fetchCount());
            diffBatch.setScheduled(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.SCHEDULED))
                        .fetchCount());
            diffBatch.setInProcess(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.IN_PROCESS))
                        .fetchCount());

            diffBatches.add(diffBatch);
        }

        return diffBatches;
    }

    private HibernateQuery<DiffTask> batchIDQuery(String batchID) {
        return new HibernateQuery<DiffTask>(em.unwrap(Session.class))
                .from(QDiffTask.diffTask)
                .leftJoin(QDiffTask.diffTask.queueMessage, QQueueMessage.queueMessage)
                .where(QQueueMessage.queueMessage.batchID.eq(batchID));
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Iterator<DiffTask> listDiffTasks(
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam, int offset, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        MatchTask matchTask = new MatchTask(cb);
        TypedQuery<DiffTask> query = em.createQuery(select(cb, matchTask, queueTaskQueryParam, diffTaskQueryParam))
                .setHint(QueryHints.FETCH_SIZE, queryFetchSize());
        if (offset > 0)
            query.setFirstResult(offset);
        if (limit > 0)
            query.setMaxResults(limit);
        return query.getResultStream().iterator();
    }

    public long countTasks(TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        MatchTask matchTask = new MatchTask(cb);

        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        diffTask = q.from(DiffTask.class);
        queueMsg = diffTask.join(DiffTask_.queueMessage);

        return em.createQuery(
                restrict(queueTaskQueryParam, diffTaskQueryParam, matchTask, q).select(cb.count(diffTask)))
                .getSingleResult();
    }

    private <T> CriteriaQuery<T> restrict(
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam, MatchTask matchTask, CriteriaQuery<T> q) {
        List<javax.persistence.criteria.Predicate> predicates = matchTask.diffPredicates(
                queueMsg,
                diffTask,
                queueTaskQueryParam,
                diffTaskQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        return q;
    }

    private int queryFetchSize() {
        return device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class).getQueryFetchSize();
    }

    private CriteriaQuery<DiffTask> select(
            CriteriaBuilder cb, MatchTask matchTask, TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        CriteriaQuery<DiffTask> q = cb.createQuery(DiffTask.class);
        diffTask = q.from(DiffTask.class);
        queueMsg = diffTask.join(DiffTask_.queueMessage);

        q = restrict(queueTaskQueryParam, diffTaskQueryParam, matchTask, q);
        if (diffTaskQueryParam.getOrderBy() != null)
            q.orderBy(matchTask.diffTaskOrder(diffTaskQueryParam.getOrderBy(), diffTask));

        return q.select(diffTask);
    }
    
}
