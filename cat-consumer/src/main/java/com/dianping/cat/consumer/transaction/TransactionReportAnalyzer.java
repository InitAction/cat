/**
 * 
 */
package com.dianping.cat.consumer.transaction;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;

import com.dianping.cat.configuration.model.entity.Config;
import com.dianping.cat.configuration.model.entity.Property;
import com.dianping.cat.consumer.transaction.model.entity.TransactionName;
import com.dianping.cat.consumer.transaction.model.entity.TransactionReport;
import com.dianping.cat.consumer.transaction.model.entity.TransactionType;
import com.dianping.cat.consumer.transaction.model.transform.DefaultJsonBuilder;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.AbstractMessageAnalyzer;
import com.dianping.cat.message.spi.MessageManager;
import com.dianping.cat.message.spi.MessageTree;
import com.site.helper.Files;
import com.site.lookup.annotation.Inject;

/**
 * @author sean.wang
 * @since Jan 5, 2012
 */
public class TransactionReportAnalyzer extends AbstractMessageAnalyzer<TransactionReport> implements Initializable, LogEnabled {
	private final static long MINUTE = 60 * 1000L;

	private static final SimpleDateFormat FILE_SDF = new SimpleDateFormat("yyyyMMddHHmm");

	@Inject
	private MessageManager m_manager;

	private TransactionReport report;

	private long m_extraTime;

	private String m_reportPath;

	private Logger m_logger;

	private void computeMeanSquareDeviation() {
		Collection<TransactionType> types = report.getTypes().values();

		for (TransactionType type : types) {
			long typeCount = 0;
			long typeFailCount = 0;
			double typeSum = 0;
			double typeSum2 = 0;
			Collection<TransactionName> names = type.getNames().values();
			for (TransactionName name : names) {
				long count = name.getTotalCount();
				double sum = name.getSum();
				double ave = sum / count;
				double sum2 = name.getSum2();
				double std = std(count, ave, sum2);
				long failCount = name.getFailCount();
				double failPercent = 100.0 * failCount / count;
				name.setFailPercent(failPercent);
				name.setAvg(ave);
				name.setStd(std);
				typeCount += count;
				typeSum += sum;
				typeSum2 += sum2;
				typeFailCount += failCount;
				if (name.getSuccessMessageId() != null) {
					type.setSuccessMessageId(name.getSuccessMessageId());
				}
				if (name.getFailMessageId() != null) {
					type.setFailMessageId(name.getFailMessageId());
				}
				type.setMax(Math.max(name.getMax(), type.getMax()));
				type.setMin(Math.min(name.getMin(), type.getMin()));
			}
			type.setTotalCount(typeCount);
			type.setFailCount(typeFailCount);
			type.setSum(typeSum);
			type.setSum2(typeSum2);
			double typeAvg = typeSum / typeCount;
			type.setAvg(typeAvg);
			type.setFailPercent(100.0 * typeFailCount / typeCount);
			type.setStd(std(typeCount, typeAvg, typeSum2));
		}
	}

	/**
	 * @param count
	 * @param ave
	 * @param sum2
	 * @return
	 */
	public double std(long count, double ave, double sum2) {
		return Math.sqrt(sum2 / count - 2 * ave * ave + ave * ave);
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public TransactionReport generate() {
		computeMeanSquareDeviation();
		return report;
	}

	// TODO
	private String getTransactionFileName(TransactionReport report) {
		StringBuffer result = new StringBuffer();
		String start = FILE_SDF.format(report.getStartTime());
		String end = FILE_SDF.format(report.getEndTime());

		result.append(report.getDomain()).append("-").append(start).append("-").append(end);
		return result.toString();
	}

	@Override
	public void initialize() throws InitializationException {
		Config config = m_manager.getClientConfig();

		if (config != null) {
			Property property = config.findProperty("transaction-base-dir");

			if (property != null) {
				m_reportPath = property.getValue();
			}
		}
	}

	@Override
	protected boolean isTimeout() {
		long endTime = report.getEndTime().getTime();
		long currentTime = System.currentTimeMillis();

		if (currentTime > endTime + m_extraTime) {
			return true;
		}
		return false;
	}

	private void process(Message message, String messageId) {
		if (message instanceof Transaction) {
			Transaction t = (Transaction) message;
			String tType = t.getType();
			String tName = t.getName();
			TransactionType type = report.getTypes().get(tType);
			if (type == null) {
				type = new TransactionType(tType);
				report.addType(type);
			}
			TransactionName name = type.getNames().get(tName);
			if (name == null) {
				name = new TransactionName(tName);
				type.addName(name);
			}
			name.setTotalCount(name.getTotalCount() + 1);
			if (!t.isSuccess()) {
				name.setFailCount(name.getFailCount() + 1);
			}
			if (messageId != null) {
				if (t.isSuccess()) {
					name.setSuccessMessageId(messageId);
				} else {
					name.setFailMessageId(messageId);
				}
			}
			long duration = t.getDuration();
			name.setMax(Math.max(name.getMax(), duration));
			name.setMin(Math.min(name.getMin(), duration));
			name.setSum(name.getSum() + duration);
			name.setSum2(name.getSum2() + duration * duration);
			if (!t.hasChildren()) {
				return;
			}
			List<Message> children = t.getChildren();
			for (Message child : children) {
				process(child, null);
			}
		}
	}

	@Override
	protected void process(MessageTree tree) {
		if (report == null) {
			this.report = new TransactionReport(tree.getDomain());
		}
		Message message = tree.getMessage();
		process(message, tree.getMessageId());
	}

	public void setAnalyzerInfo(long startTime, long duration, String domain, long extraTime) {
		report = new TransactionReport(domain);
		report.setStartTime(new Date(startTime));
		report.setEndTime(new Date(startTime + duration - MINUTE));
		m_extraTime = extraTime;
	}

	public void setReportPath(String configPath) {
		m_reportPath = configPath;
	}

	@Override
	protected void store(TransactionReport result) {
		String failureFileName = getTransactionFileName(report);
		String htmlPath = new StringBuilder().append(m_reportPath).append(failureFileName).append(".html").toString();
		File file = new File(htmlPath);

		file.getParentFile().mkdirs();

		DefaultJsonBuilder builder = new DefaultJsonBuilder();

		report.accept(builder);

		try {
			Files.forIO().writeTo(file, builder.getString());
		} catch (IOException e) {
			m_logger.error(String.format("Error when writing to file(%s)!", file), e);
		}
	}
}
