package net.i2p.router.web;

import java.util.HashMap;
import java.util.Map;

/**
 * Form handler for the transport tuning page.
 * Persists min/max/step ranges to router.config without restart.
 *
 * @since 0.9.70+
 */
public class TuningFormHandler extends FormHandler {

    // property prefix
    private static final String PREFIX = "tuner.";

    // form field values (set by jsp:setProperty)
    private String _ackFrequencyMin;
    private String _ackFrequencyMax;
    private String _ackFrequencyStep;
    private String _dataMessageTimeoutMin;
    private String _dataMessageTimeoutMax;
    private String _dataMessageTimeoutStep;
    private String _obEstablishTimeMin;
    private String _obEstablishTimeMax;
    private String _obEstablishTimeStep;
    private String _ibEstablishTimeMin;
    private String _ibEstablishTimeMax;
    private String _ibEstablishTimeStep;
    private String _requeueTimeMin;
    private String _requeueTimeMax;
    private String _requeueTimeStep;
    private String _replenishFrequencyMin;
    private String _replenishFrequencyMax;
    private String _replenishFrequencyStep;
    private String _selectorLoopDelayMin;
    private String _selectorLoopDelayMax;
    private String _selectorLoopDelayStep;
    private String _obMsgsPerPumpMin;
    private String _obMsgsPerPumpMax;
    private String _obMsgsPerPumpStep;
    private String _ibMsgsPerPumpMin;
    private String _ibMsgsPerPumpMax;
    private String _ibMsgsPerPumpStep;
    private String _initialWindowSizeMin;
    private String _initialWindowSizeMax;
    private String _initialWindowSizeStep;
    private String _initialRTOMin;
    private String _initialRTOMax;
    private String _initialRTOStep;
    private String _initialAckDelayMin;
    private String _initialAckDelayMax;
    private String _initialAckDelayStep;
    private String _passiveFlushDelayMin;
    private String _passiveFlushDelayMax;
    private String _passiveFlushDelayStep;
    private String _writerQueueSizeMin;
    private String _writerQueueSizeMax;
    private String _writerQueueSizeStep;
    private String _codelTargetMin;
    private String _codelTargetMax;
    private String _codelTargetStep;
    private String _codelIntervalMin;
    private String _codelIntervalMax;
    private String _codelIntervalStep;
    private String _westwoodDecayFactorMin;
    private String _westwoodDecayFactorMax;
    private String _westwoodDecayFactorStep;
    private String _maxSlowStartWindowMin;
    private String _maxSlowStartWindowMax;
    private String _maxSlowStartWindowStep;
    private String _xdhPreCalcMinMin;
    private String _xdhPreCalcMinMax;
    private String _xdhPreCalcMinStep;
    private String _ntcpThreadsMin;
    private String _ntcpThreadsMax;
    private String _ntcpThreadsStep;
    private String _ntcpQueueCapacityMin;
    private String _ntcpQueueCapacityMax;
    private String _ntcpQueueCapacityStep;
    private String _udpHandlerThreadsMin;
    private String _udpHandlerThreadsMax;
    private String _udpHandlerThreadsStep;
    private String _peerOutboundQueueMin;
    private String _peerOutboundQueueMax;
    private String _peerOutboundQueueStep;
    private String _transitThrottleFactorMin;
    private String _transitThrottleFactorMax;
    private String _transitThrottleFactorStep;
    private String _throttleRejectExponentMin;
    private String _throttleRejectExponentMax;
    private String _throttleRejectExponentStep;
    private String _maxParticipatingTunnelsMin;
    private String _maxParticipatingTunnelsMax;
    private String _maxParticipatingTunnelsStep;
    private String _buildHandlerMaxQueueMin;
    private String _buildHandlerMaxQueueMax;
    private String _buildHandlerMaxQueueStep;
    private String _goodDeficitThrottleMin;
    private String _goodDeficitThrottleMax;
    private String _goodDeficitThrottleStep;
    private String _perTunnelBweDivisorMin;
    private String _perTunnelBweDivisorMax;
    private String _perTunnelBweDivisorStep;
    private String _tunnelGrowthFactorMin;
    private String _tunnelGrowthFactorMax;
    private String _tunnelGrowthFactorStep;
    private String _maxRTOMin;
    private String _maxRTOMax;
    private String _maxRTOStep;
    private String _maxResendDelayMin;
    private String _maxResendDelayMax;
    private String _maxResendDelayStep;
    private String _maxRetransmissionsMin;
    private String _maxRetransmissionsMax;
    private String _maxRetransmissionsStep;
    private String _netDBSearchLimitMin;
    private String _netDBSearchLimitMax;
    private String _netDBSearchLimitStep;
    private String _netDBMaxConcurrentMin;
    private String _netDBMaxConcurrentMax;
    private String _netDBMaxConcurrentStep;
    private String _netDBSingleSearchTimeMin;
    private String _netDBSingleSearchTimeMax;
    private String _netDBSingleSearchTimeStep;
    private String _maxConcurrentEstablishMin;
    private String _maxConcurrentEstablishMax;
    private String _maxConcurrentEstablishStep;
    private String _maxProfilesMin;
    private String _maxProfilesMax;
    private String _maxProfilesStep;
    private String _minFastPeersMin;
    private String _minFastPeersMax;
    private String _minFastPeersStep;
    private String _buildRequestTimeoutMin;
    private String _buildRequestTimeoutMax;
    private String _buildRequestTimeoutStep;
    private String _buildFirstHopTimeoutMin;
    private String _buildFirstHopTimeoutMax;
    private String _buildFirstHopTimeoutStep;

    // setters - called by jsp:setProperty
    public void setAckFrequencyMin(String v) { _ackFrequencyMin = v; }
    public void setAckFrequencyMax(String v) { _ackFrequencyMax = v; }
    public void setAckFrequencyStep(String v) { _ackFrequencyStep = v; }
    public void setDataMessageTimeoutMin(String v) { _dataMessageTimeoutMin = v; }
    public void setDataMessageTimeoutMax(String v) { _dataMessageTimeoutMax = v; }
    public void setDataMessageTimeoutStep(String v) { _dataMessageTimeoutStep = v; }
    public void setObEstablishTimeMin(String v) { _obEstablishTimeMin = v; }
    public void setObEstablishTimeMax(String v) { _obEstablishTimeMax = v; }
    public void setObEstablishTimeStep(String v) { _obEstablishTimeStep = v; }
    public void setIbEstablishTimeMin(String v) { _ibEstablishTimeMin = v; }
    public void setIbEstablishTimeMax(String v) { _ibEstablishTimeMax = v; }
    public void setIbEstablishTimeStep(String v) { _ibEstablishTimeStep = v; }
    public void setRequeueTimeMin(String v) { _requeueTimeMin = v; }
    public void setRequeueTimeMax(String v) { _requeueTimeMax = v; }
    public void setRequeueTimeStep(String v) { _requeueTimeStep = v; }
    public void setReplenishFrequencyMin(String v) { _replenishFrequencyMin = v; }
    public void setReplenishFrequencyMax(String v) { _replenishFrequencyMax = v; }
    public void setReplenishFrequencyStep(String v) { _replenishFrequencyStep = v; }
    public void setSelectorLoopDelayMin(String v) { _selectorLoopDelayMin = v; }
    public void setSelectorLoopDelayMax(String v) { _selectorLoopDelayMax = v; }
    public void setSelectorLoopDelayStep(String v) { _selectorLoopDelayStep = v; }
    public void setObMsgsPerPumpMin(String v) { _obMsgsPerPumpMin = v; }
    public void setObMsgsPerPumpMax(String v) { _obMsgsPerPumpMax = v; }
    public void setObMsgsPerPumpStep(String v) { _obMsgsPerPumpStep = v; }
    public void setIbMsgsPerPumpMin(String v) { _ibMsgsPerPumpMin = v; }
    public void setIbMsgsPerPumpMax(String v) { _ibMsgsPerPumpMax = v; }
    public void setIbMsgsPerPumpStep(String v) { _ibMsgsPerPumpStep = v; }
    public void setInitialWindowSizeMin(String v) { _initialWindowSizeMin = v; }
    public void setInitialWindowSizeMax(String v) { _initialWindowSizeMax = v; }
    public void setInitialWindowSizeStep(String v) { _initialWindowSizeStep = v; }
    public void setInitialRTOMin(String v) { _initialRTOMin = v; }
    public void setInitialRTOMax(String v) { _initialRTOMax = v; }
    public void setInitialRTOStep(String v) { _initialRTOStep = v; }
    public void setInitialAckDelayMin(String v) { _initialAckDelayMin = v; }
    public void setInitialAckDelayMax(String v) { _initialAckDelayMax = v; }
    public void setInitialAckDelayStep(String v) { _initialAckDelayStep = v; }
    public void setPassiveFlushDelayMin(String v) { _passiveFlushDelayMin = v; }
    public void setPassiveFlushDelayMax(String v) { _passiveFlushDelayMax = v; }
    public void setPassiveFlushDelayStep(String v) { _passiveFlushDelayStep = v; }
    public void setWriterQueueSizeMin(String v) { _writerQueueSizeMin = v; }
    public void setWriterQueueSizeMax(String v) { _writerQueueSizeMax = v; }
    public void setWriterQueueSizeStep(String v) { _writerQueueSizeStep = v; }
    public void setCodelTargetMin(String v) { _codelTargetMin = v; }
    public void setCodelTargetMax(String v) { _codelTargetMax = v; }
    public void setCodelTargetStep(String v) { _codelTargetStep = v; }
    public void setCodelIntervalMin(String v) { _codelIntervalMin = v; }
    public void setCodelIntervalMax(String v) { _codelIntervalMax = v; }
    public void setCodelIntervalStep(String v) { _codelIntervalStep = v; }
    public void setWestwoodDecayFactorMin(String v) { _westwoodDecayFactorMin = v; }
    public void setWestwoodDecayFactorMax(String v) { _westwoodDecayFactorMax = v; }
    public void setWestwoodDecayFactorStep(String v) { _westwoodDecayFactorStep = v; }
    public void setMaxSlowStartWindowMin(String v) { _maxSlowStartWindowMin = v; }
    public void setMaxSlowStartWindowMax(String v) { _maxSlowStartWindowMax = v; }
    public void setMaxSlowStartWindowStep(String v) { _maxSlowStartWindowStep = v; }
    public void setXdhPreCalcMinMin(String v) { _xdhPreCalcMinMin = v; }
    public void setXdhPreCalcMinMax(String v) { _xdhPreCalcMinMax = v; }
    public void setXdhPreCalcMinStep(String v) { _xdhPreCalcMinStep = v; }
    public void setNtcpThreadsMin(String v) { _ntcpThreadsMin = v; }
    public void setNtcpThreadsMax(String v) { _ntcpThreadsMax = v; }
    public void setNtcpThreadsStep(String v) { _ntcpThreadsStep = v; }
    public void setNtcpQueueCapacityMin(String v) { _ntcpQueueCapacityMin = v; }
    public void setNtcpQueueCapacityMax(String v) { _ntcpQueueCapacityMax = v; }
    public void setNtcpQueueCapacityStep(String v) { _ntcpQueueCapacityStep = v; }
    public void setUdpHandlerThreadsMin(String v) { _udpHandlerThreadsMin = v; }
    public void setUdpHandlerThreadsMax(String v) { _udpHandlerThreadsMax = v; }
    public void setUdpHandlerThreadsStep(String v) { _udpHandlerThreadsStep = v; }
    public void setPeerOutboundQueueMin(String v) { _peerOutboundQueueMin = v; }
    public void setPeerOutboundQueueMax(String v) { _peerOutboundQueueMax = v; }
    public void setPeerOutboundQueueStep(String v) { _peerOutboundQueueStep = v; }
    public void setTransitThrottleFactorMin(String v) { _transitThrottleFactorMin = v; }
    public void setTransitThrottleFactorMax(String v) { _transitThrottleFactorMax = v; }
    public void setTransitThrottleFactorStep(String v) { _transitThrottleFactorStep = v; }
    public void setThrottleRejectExponentMin(String v) { _throttleRejectExponentMin = v; }
    public void setThrottleRejectExponentMax(String v) { _throttleRejectExponentMax = v; }
    public void setThrottleRejectExponentStep(String v) { _throttleRejectExponentStep = v; }
    public void setMaxParticipatingTunnelsMin(String v) { _maxParticipatingTunnelsMin = v; }
    public void setMaxParticipatingTunnelsMax(String v) { _maxParticipatingTunnelsMax = v; }
    public void setMaxParticipatingTunnelsStep(String v) { _maxParticipatingTunnelsStep = v; }
    public void setBuildHandlerMaxQueueMin(String v) { _buildHandlerMaxQueueMin = v; }
    public void setBuildHandlerMaxQueueMax(String v) { _buildHandlerMaxQueueMax = v; }
    public void setBuildHandlerMaxQueueStep(String v) { _buildHandlerMaxQueueStep = v; }
    public void setGoodDeficitThrottleMin(String v) { _goodDeficitThrottleMin = v; }
    public void setGoodDeficitThrottleMax(String v) { _goodDeficitThrottleMax = v; }
    public void setGoodDeficitThrottleStep(String v) { _goodDeficitThrottleStep = v; }
    public void setPerTunnelBweDivisorMin(String v) { _perTunnelBweDivisorMin = v; }
    public void setPerTunnelBweDivisorMax(String v) { _perTunnelBweDivisorMax = v; }
    public void setPerTunnelBweDivisorStep(String v) { _perTunnelBweDivisorStep = v; }
    public void setTunnelGrowthFactorMin(String v) { _tunnelGrowthFactorMin = v; }
    public void setTunnelGrowthFactorMax(String v) { _tunnelGrowthFactorMax = v; }
    public void setTunnelGrowthFactorStep(String v) { _tunnelGrowthFactorStep = v; }
    public void setMaxRTOMin(String v) { _maxRTOMin = v; }
    public void setMaxRTOMax(String v) { _maxRTOMax = v; }
    public void setMaxRTOStep(String v) { _maxRTOStep = v; }
    public void setMaxResendDelayMin(String v) { _maxResendDelayMin = v; }
    public void setMaxResendDelayMax(String v) { _maxResendDelayMax = v; }
    public void setMaxResendDelayStep(String v) { _maxResendDelayStep = v; }
    public void setMaxRetransmissionsMin(String v) { _maxRetransmissionsMin = v; }
    public void setMaxRetransmissionsMax(String v) { _maxRetransmissionsMax = v; }
    public void setMaxRetransmissionsStep(String v) { _maxRetransmissionsStep = v; }
    public void setNetDBSearchLimitMin(String v) { _netDBSearchLimitMin = v; }
    public void setNetDBSearchLimitMax(String v) { _netDBSearchLimitMax = v; }
    public void setNetDBSearchLimitStep(String v) { _netDBSearchLimitStep = v; }
    public void setNetDBMaxConcurrentMin(String v) { _netDBMaxConcurrentMin = v; }
    public void setNetDBMaxConcurrentMax(String v) { _netDBMaxConcurrentMax = v; }
    public void setNetDBMaxConcurrentStep(String v) { _netDBMaxConcurrentStep = v; }
    public void setNetDBSingleSearchTimeMin(String v) { _netDBSingleSearchTimeMin = v; }
    public void setNetDBSingleSearchTimeMax(String v) { _netDBSingleSearchTimeMax = v; }
    public void setNetDBSingleSearchTimeStep(String v) { _netDBSingleSearchTimeStep = v; }
    public void setMaxConcurrentEstablishMin(String v) { _maxConcurrentEstablishMin = v; }
    public void setMaxConcurrentEstablishMax(String v) { _maxConcurrentEstablishMax = v; }
    public void setMaxConcurrentEstablishStep(String v) { _maxConcurrentEstablishStep = v; }
    public void setMaxProfilesMin(String v) { _maxProfilesMin = v; }
    public void setMaxProfilesMax(String v) { _maxProfilesMax = v; }
    public void setMaxProfilesStep(String v) { _maxProfilesStep = v; }
    public void setMinFastPeersMin(String v) { _minFastPeersMin = v; }
    public void setMinFastPeersMax(String v) { _minFastPeersMax = v; }
    public void setMinFastPeersStep(String v) { _minFastPeersStep = v; }
    public void setBuildRequestTimeoutMin(String v) { _buildRequestTimeoutMin = v; }
    public void setBuildRequestTimeoutMax(String v) { _buildRequestTimeoutMax = v; }
    public void setBuildRequestTimeoutStep(String v) { _buildRequestTimeoutStep = v; }
    public void setBuildFirstHopTimeoutMin(String v) { _buildFirstHopTimeoutMin = v; }
    public void setBuildFirstHopTimeoutMax(String v) { _buildFirstHopTimeoutMax = v; }
    public void setBuildFirstHopTimeoutStep(String v) { _buildFirstHopTimeoutStep = v; }

    // Auto-tuning override setters (checkbox: -1 = auto, >= 0 = manual lock)
    public void setAckFrequencyOverride(String v) { _ackFrequencyOverride = v; }
    public void setDataMessageTimeoutOverride(String v) { _dataMessageTimeoutOverride = v; }
    public void setObEstablishTimeOverride(String v) { _obEstablishTimeOverride = v; }
    public void setIbEstablishTimeOverride(String v) { _ibEstablishTimeOverride = v; }
    public void setRequeueTimeOverride(String v) { _requeueTimeOverride = v; }
    public void setReplenishFrequencyOverride(String v) { _replenishFrequencyOverride = v; }
    public void setSelectorLoopDelayOverride(String v) { _selectorLoopDelayOverride = v; }
    public void setObMsgsPerPumpOverride(String v) { _obMsgsPerPumpOverride = v; }
    public void setIbMsgsPerPumpOverride(String v) { _ibMsgsPerPumpOverride = v; }
    public void setInitialWindowSizeOverride(String v) { _initialWindowSizeOverride = v; }
    public void setInitialRTOOverride(String v) { _initialRTOOverride = v; }
    public void setInitialAckDelayOverride(String v) { _initialAckDelayOverride = v; }
    public void setPassiveFlushDelayOverride(String v) { _passiveFlushDelayOverride = v; }
    public void setMaxSlowStartWindowOverride(String v) { _maxSlowStartWindowOverride = v; }
    public void setWriterQueueSizeOverride(String v) { _writerQueueSizeOverride = v; }
    public void setCodelTargetOverride(String v) { _codelTargetOverride = v; }
    public void setCodelIntervalOverride(String v) { _codelIntervalOverride = v; }
    public void setWestwoodDecayFactorOverride(String v) { _westwoodDecayFactorOverride = v; }
    public void setXdhPreCalcMinOverride(String v) { _xdhPreCalcMinOverride = v; }
    public void setNtcpThreadsOverride(String v) { _ntcpThreadsOverride = v; }
    public void setNtcpQueueCapacityOverride(String v) { _ntcpQueueCapacityOverride = v; }
    public void setUdpHandlerThreadsOverride(String v) { _udpHandlerThreadsOverride = v; }
    public void setPeerOutboundQueueOverride(String v) { _peerOutboundQueueOverride = v; }
    public void setTransitThrottleFactorOverride(String v) { _transitThrottleFactorOverride = v; }
    public void setThrottleRejectExponentOverride(String v) { _throttleRejectExponentOverride = v; }
    public void setMaxParticipatingTunnelsOverride(String v) { _maxParticipatingTunnelsOverride = v; }
    public void setBuildHandlerMaxQueueOverride(String v) { _buildHandlerMaxQueueOverride = v; }
    public void setGoodDeficitThrottleOverride(String v) { _goodDeficitThrottleOverride = v; }
    public void setPerTunnelBweDivisorOverride(String v) { _perTunnelBweDivisorOverride = v; }
    public void setTunnelGrowthFactorOverride(String v) { _tunnelGrowthFactorOverride = v; }
    public void setMaxRTOOverride(String v) { _maxRTOOverride = v; }
    public void setMaxResendDelayOverride(String v) { _maxResendDelayOverride = v; }
    public void setMaxRetransmissionsOverride(String v) { _maxRetransmissionsOverride = v; }
    public void setNetDBSearchLimitOverride(String v) { _netDBSearchLimitOverride = v; }
    public void setNetDBMaxConcurrentOverride(String v) { _netDBMaxConcurrentOverride = v; }
    public void setNetDBSingleSearchTimeOverride(String v) { _netDBSingleSearchTimeOverride = v; }
    public void setMaxConcurrentEstablishOverride(String v) { _maxConcurrentEstablishOverride = v; }
    public void setMaxProfilesOverride(String v) { _maxProfilesOverride = v; }
    public void setMinFastPeersOverride(String v) { _minFastPeersOverride = v; }
    public void setBuildRequestTimeoutOverride(String v) { _buildRequestTimeoutOverride = v; }
    public void setBuildFirstHopTimeoutOverride(String v) { _buildFirstHopTimeoutOverride = v; }

    @Override
    protected void processForm() {
        if (_action == null || !_action.equals(_t("Save")))
            return;

        Map<String, String> changes = new HashMap<String, String>();

        // Transport
        saveField(changes, "ACK_FREQUENCY", "Min", _ackFrequencyMin, 20);
        saveField(changes, "ACK_FREQUENCY", "Max", _ackFrequencyMax, 100);
        saveField(changes, "ACK_FREQUENCY", "Step", _ackFrequencyStep, 5);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Min", _dataMessageTimeoutMin, 1000);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Max", _dataMessageTimeoutMax, 10000);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Step", _dataMessageTimeoutStep, 500);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Min", _obEstablishTimeMin, 1000);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Max", _obEstablishTimeMax, 5000);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Step", _obEstablishTimeStep, 500);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Min", _ibEstablishTimeMin, 1000);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Max", _ibEstablishTimeMax, 8000);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Step", _ibEstablishTimeStep, 500);

        // Tunnel
        saveField(changes, "REQUEUE_TIME", "Min", _requeueTimeMin, 10);
        saveField(changes, "REQUEUE_TIME", "Max", _requeueTimeMax, 100);
        saveField(changes, "REQUEUE_TIME", "Step", _requeueTimeStep, 5);
        saveField(changes, "REPLENISH_FREQUENCY", "Min", _replenishFrequencyMin, 5);
        saveField(changes, "REPLENISH_FREQUENCY", "Max", _replenishFrequencyMax, 100);
        saveField(changes, "REPLENISH_FREQUENCY", "Step", _replenishFrequencyStep, 5);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Min", _selectorLoopDelayMin, 1);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Max", _selectorLoopDelayMax, 20);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Step", _selectorLoopDelayStep, 1);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Min", _obMsgsPerPumpMin, 32);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Max", _obMsgsPerPumpMax, 512);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Step", _obMsgsPerPumpStep, 16);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Min", _ibMsgsPerPumpMin, 16);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Max", _ibMsgsPerPumpMax, 256);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Step", _ibMsgsPerPumpStep, 8);

        // Streaming
        saveField(changes, "INITIAL_WINDOW_SIZE", "Min", _initialWindowSizeMin, 4);
        saveField(changes, "INITIAL_WINDOW_SIZE", "Max", _initialWindowSizeMax, 64);
        saveField(changes, "INITIAL_WINDOW_SIZE", "Step", _initialWindowSizeStep, 4);
        saveField(changes, "INITIAL_RTO", "Min", _initialRTOMin, 2000);
        saveField(changes, "INITIAL_RTO", "Max", _initialRTOMax, 15000);
        saveField(changes, "INITIAL_RTO", "Step", _initialRTOStep, 500);
        saveField(changes, "INITIAL_ACK_DELAY", "Min", _initialAckDelayMin, 5);
        saveField(changes, "INITIAL_ACK_DELAY", "Max", _initialAckDelayMax, 100);
        saveField(changes, "INITIAL_ACK_DELAY", "Step", _initialAckDelayStep, 5);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Min", _passiveFlushDelayMin, 10);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Max", _passiveFlushDelayMax, 300);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Step", _passiveFlushDelayStep, 10);

        // I2CP
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Min", _writerQueueSizeMin, 32);
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Max", _writerQueueSizeMax, 1024);
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Step", _writerQueueSizeStep, 32);

        // CoDel
        saveField(changes, "CODEL_TARGET", "Min", _codelTargetMin, 1);
        saveField(changes, "CODEL_TARGET", "Max", _codelTargetMax, 20);
        saveField(changes, "CODEL_TARGET", "Step", _codelTargetStep, 1);
        saveField(changes, "CODEL_INTERVAL", "Min", _codelIntervalMin, 10);
        saveField(changes, "CODEL_INTERVAL", "Max", _codelIntervalMax, 200);
        saveField(changes, "CODEL_INTERVAL", "Step", _codelIntervalStep, 10);

        // Westwood
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Min", _westwoodDecayFactorMin, 2);
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Max", _westwoodDecayFactorMax, 16);
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Step", _westwoodDecayFactorStep, 1);

        // Streaming (continued)
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Min", _maxSlowStartWindowMin, 8);
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Max", _maxSlowStartWindowMax, 128);
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Step", _maxSlowStartWindowStep, 4);

        // Buffers & Threads
        saveField(changes, "crypto.x25519.precalcMin", "Min", _xdhPreCalcMinMin, 2);
        saveField(changes, "crypto.x25519.precalcMin", "Max", _xdhPreCalcMinMax, 128);
        saveField(changes, "crypto.x25519.precalcMin", "Step", _xdhPreCalcMinStep, 2);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Min", _ntcpThreadsMin, 1);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Max", _ntcpThreadsMax, 8);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Step", _ntcpThreadsStep, 1);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Min", _ntcpQueueCapacityMin, 256);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Max", _ntcpQueueCapacityMax, 16384);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Step", _ntcpQueueCapacityStep, 256);
        saveField(changes, "udp.packetHandler.maxThreads", "Min", _udpHandlerThreadsMin, 1);
        saveField(changes, "udp.packetHandler.maxThreads", "Max", _udpHandlerThreadsMax, 16);
        saveField(changes, "udp.packetHandler.maxThreads", "Step", _udpHandlerThreadsStep, 1);
        saveField(changes, "router.peerOutboundQueueSize", "Min", _peerOutboundQueueMin, 50);
        saveField(changes, "router.peerOutboundQueueSize", "Max", _peerOutboundQueueMax, 500);
        saveField(changes, "router.peerOutboundQueueSize", "Step", _peerOutboundQueueStep, 50);

        // Router Core
        saveField(changes, "router.transitThrottleFactor", "Min", _transitThrottleFactorMin, 10);
        saveField(changes, "router.transitThrottleFactor", "Max", _transitThrottleFactorMax, 100);
        saveField(changes, "router.transitThrottleFactor", "Step", _transitThrottleFactorStep, 5);
        saveField(changes, "router.throttleRejectExponent", "Min", _throttleRejectExponentMin, 2);
        saveField(changes, "router.throttleRejectExponent", "Max", _throttleRejectExponentMax, 30);
        saveField(changes, "router.throttleRejectExponent", "Step", _throttleRejectExponentStep, 1);
        saveField(changes, "router.maxParticipatingTunnels", "Min", _maxParticipatingTunnelsMin, 500);
        saveField(changes, "router.maxParticipatingTunnels", "Max", _maxParticipatingTunnelsMax, 15000);
        saveField(changes, "router.maxParticipatingTunnels", "Step", _maxParticipatingTunnelsStep, 500);
        saveField(changes, "router.buildHandlerMaxQueue", "Min", _buildHandlerMaxQueueMin, 16);
        saveField(changes, "router.buildHandlerMaxQueue", "Max", _buildHandlerMaxQueueMax, 2048);
        saveField(changes, "router.buildHandlerMaxQueue", "Step", _buildHandlerMaxQueueStep, 32);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Min", _goodDeficitThrottleMin, 5000);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Max", _goodDeficitThrottleMax, 60000);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Step", _goodDeficitThrottleStep, 5000);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Min", _perTunnelBweDivisorMin, 10);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Max", _perTunnelBweDivisorMax, 500);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Step", _perTunnelBweDivisorStep, 10);
        saveField(changes, "router.tunnelGrowthFactor", "Min", _tunnelGrowthFactorMin, 10);
        saveField(changes, "router.tunnelGrowthFactor", "Max", _tunnelGrowthFactorMax, 80);
        saveField(changes, "router.tunnelGrowthFactor", "Step", _tunnelGrowthFactorStep, 5);

        // Streaming congestion
        saveField(changes, "i2p.streaming.maxRTO", "Min", _maxRTOMin, 3000);
        saveField(changes, "i2p.streaming.maxRTO", "Max", _maxRTOMax, 60000);
        saveField(changes, "i2p.streaming.maxRTO", "Step", _maxRTOStep, 1000);
        saveField(changes, "i2p.streaming.maxResendDelay", "Min", _maxResendDelayMin, 2000);
        saveField(changes, "i2p.streaming.maxResendDelay", "Max", _maxResendDelayMax, 60000);
        saveField(changes, "i2p.streaming.maxResendDelay", "Step", _maxResendDelayStep, 1000);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Min", _maxRetransmissionsMin, 8);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Max", _maxRetransmissionsMax, 256);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Step", _maxRetransmissionsStep, 8);

        // NetDB
        saveField(changes, "netdb.searchLimit", "Min", _netDBSearchLimitMin, 4);
        saveField(changes, "netdb.searchLimit", "Max", _netDBSearchLimitMax, 64);
        saveField(changes, "netdb.searchLimit", "Step", _netDBSearchLimitStep, 2);
        saveField(changes, "netdb.maxConcurrent", "Min", _netDBMaxConcurrentMin, 1);
        saveField(changes, "netdb.maxConcurrent", "Max", _netDBMaxConcurrentMax, 64);
        saveField(changes, "netdb.maxConcurrent", "Step", _netDBMaxConcurrentStep, 1);
        saveField(changes, "netdb.singleSearchTime", "Min", _netDBSingleSearchTimeMin, 1000);
        saveField(changes, "netdb.singleSearchTime", "Max", _netDBSingleSearchTimeMax, 15000);
        saveField(changes, "netdb.singleSearchTime", "Step", _netDBSingleSearchTimeStep, 500);

        // Transport
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Min", _maxConcurrentEstablishMin, 64);
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Max", _maxConcurrentEstablishMax, 2048);
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Step", _maxConcurrentEstablishStep, 32);

        // Peer management
        saveField(changes, "profileOrganizer.maxProfiles", "Min", _maxProfilesMin, 800);
        saveField(changes, "profileOrganizer.maxProfiles", "Max", _maxProfilesMax, 8000);
        saveField(changes, "profileOrganizer.maxProfiles", "Step", _maxProfilesStep, 200);
        saveField(changes, "profileOrganizer.minFastPeers", "Min", _minFastPeersMin, 50);
        saveField(changes, "profileOrganizer.minFastPeers", "Max", _minFastPeersMax, 2000);
        saveField(changes, "profileOrganizer.minFastPeers", "Step", _minFastPeersStep, 50);

        // Build timeouts
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Min", _buildRequestTimeoutMin, 3000);
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Max", _buildRequestTimeoutMax, 30000);
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Step", _buildRequestTimeoutStep, 1000);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Min", _buildFirstHopTimeoutMin, 2000);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Max", _buildFirstHopTimeoutMax, 20000);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Step", _buildFirstHopTimeoutStep, 1000);

        // Process auto-tuning overrides (checkbox toggle)
        net.i2p.router.Tuner tuner = getTuner();
        if (tuner != null) {
            applyOverride(tuner, "ACK_FREQUENCY", _ackFrequencyOverride);
            applyOverride(tuner, "DATA_MESSAGE_TIMEOUT", _dataMessageTimeoutOverride);
            applyOverride(tuner, "MAX_OB_ESTABLISH_TIME", _obEstablishTimeOverride);
            applyOverride(tuner, "MAX_IB_ESTABLISH_TIME", _ibEstablishTimeOverride);
            applyOverride(tuner, "REQUEUE_TIME", _requeueTimeOverride);
            applyOverride(tuner, "REPLENISH_FREQUENCY", _replenishFrequencyOverride);
            applyOverride(tuner, "SELECTOR_LOOP_DELAY", _selectorLoopDelayOverride);
            applyOverride(tuner, "MAX_OB_MSGS_PER_PUMP", _obMsgsPerPumpOverride);
            applyOverride(tuner, "MAX_IB_MSGS_PER_PUMP", _ibMsgsPerPumpOverride);
            applyOverride(tuner, "INITIAL_WINDOW_SIZE", _initialWindowSizeOverride);
            applyOverride(tuner, "INITIAL_RTO", _initialRTOOverride);
            applyOverride(tuner, "INITIAL_ACK_DELAY", _initialAckDelayOverride);
            applyOverride(tuner, "PASSIVE_FLUSH_DELAY", _passiveFlushDelayOverride);
            applyOverride(tuner, "i2p.streaming.maxSlowStartWindow", _maxSlowStartWindowOverride);
            applyOverride(tuner, "CLIENT_WRITER_QUEUE_SIZE", _writerQueueSizeOverride);
            applyOverride(tuner, "CODEL_TARGET", _codelTargetOverride);
            applyOverride(tuner, "CODEL_INTERVAL", _codelIntervalOverride);
            applyOverride(tuner, "WESTWOOD_DECAY_FACTOR", _westwoodDecayFactorOverride);
            applyOverride(tuner, "crypto.x25519.precalcMin", _xdhPreCalcMinOverride);
            applyOverride(tuner, "ntcp.sendFinisher.maxThreads", _ntcpThreadsOverride);
            applyOverride(tuner, "ntcp.sendFinisher.queueCapacity", _ntcpQueueCapacityOverride);
            applyOverride(tuner, "udp.packetHandler.maxThreads", _udpHandlerThreadsOverride);
            applyOverride(tuner, "router.peerOutboundQueueSize", _peerOutboundQueueOverride);
            applyOverride(tuner, "router.transitThrottleFactor", _transitThrottleFactorOverride);
            applyOverride(tuner, "router.throttleRejectExponent", _throttleRejectExponentOverride);
            applyOverride(tuner, "router.maxParticipatingTunnels", _maxParticipatingTunnelsOverride);
            applyOverride(tuner, "router.buildHandlerMaxQueue", _buildHandlerMaxQueueOverride);
            applyOverride(tuner, "i2p.tunnel.goodDeficitThrottle", _goodDeficitThrottleOverride);
            applyOverride(tuner, "router.tunnel.perTunnelBweDivisor", _perTunnelBweDivisorOverride);
            applyOverride(tuner, "router.tunnelGrowthFactor", _tunnelGrowthFactorOverride);
            applyOverride(tuner, "i2p.streaming.maxRTO", _maxRTOOverride);
            applyOverride(tuner, "i2p.streaming.maxResendDelay", _maxResendDelayOverride);
            applyOverride(tuner, "i2p.streaming.maxRetransmissions", _maxRetransmissionsOverride);
            applyOverride(tuner, "i2p.streaming.minResendDelay", _minResendDelayOverride);
            applyOverride(tuner, "i2p.streaming.congestionAvoidanceGrowthRateFactor", _congestionAvoidanceGrowthOverride);
            applyOverride(tuner, "i2p.streaming.slowStartGrowthRateFactor", _slowStartGrowthOverride);
            applyOverride(tuner, "netdb.searchLimit", _netDBSearchLimitOverride);
            applyOverride(tuner, "netdb.maxConcurrent", _netDBMaxConcurrentOverride);
            applyOverride(tuner, "netdb.singleSearchTime", _netDBSingleSearchTimeOverride);
            applyOverride(tuner, "i2np.udp.maxConcurrentEstablish", _maxConcurrentEstablishOverride);
            applyOverride(tuner, "profileOrganizer.maxProfiles", _maxProfilesOverride);
            applyOverride(tuner, "profileOrganizer.minFastPeers", _minFastPeersOverride);
            applyOverride(tuner, "i2p.tunnel.build.requestTimeout", _buildRequestTimeoutOverride);
            applyOverride(tuner, "i2p.tunnel.build.firstHopTimeout", _buildFirstHopTimeoutOverride);
            applyOverride(tuner, "crypto.edh.precalcMin", _edhPrecalcMinOverride);
            applyOverride(tuner, "crypto.mlkem.precalcMin", _mlkemPrecalcMinOverride);
        }

        if (!changes.isEmpty()) {
            _context.router().saveConfig(changes, null);
            addFormNotice(_t("Tuning ranges saved — changes take effect immediately"));
        } else if (tuner != null) {
            addFormNotice(_t("Tuning overrides applied"));
        } else {
            addFormNotice(_t("No changes to save"));
        }
    }

    /**
     * Save a single field if it changed from the current config value.
     * Validates that the value is a valid integer.
     */
    private void saveField(Map<String, String> changes, String param, String field,
                           String value, int defaultVal) {
        if (value == null || value.isEmpty())
            return;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            addFormError(_t("Invalid value") + ": " + param + "." + field + " = " + value);
            return;
        }
        String key = PREFIX + param + "." + field.toLowerCase();
        String oldVal = _context.getProperty(key, String.valueOf(defaultVal));
        if (!String.valueOf(parsed).equals(oldVal)) {
            changes.put(key, String.valueOf(parsed));
        }
    }

    /**
     * Apply an auto-tuning override.
     * @param tuner the Tuner instance
     * @param paramName the Tuner param name
     * @param value form value: "-1" = auto, numeric = manual lock
     */
    private void applyOverride(net.i2p.router.Tuner tuner, String paramName, String value) {
        if (value == null || value.isEmpty())
            return;
        try {
            int v = Integer.parseInt(value.trim());
            tuner.setOverride(paramName, v);
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    /**
     * Get the Tuner instance via the UDP transport.
     */
    private net.i2p.router.Tuner getTuner() {
        if (_context == null) return null;
        net.i2p.router.CommSystemFacade cs = _context.commSystem();
        if (cs == null) return null;
        java.util.SortedMap<String, net.i2p.router.transport.Transport> transports = cs.getTransports();
        net.i2p.router.transport.Transport udp = transports.get(net.i2p.router.transport.udp.UDPTransport.STYLE);
        if (udp instanceof net.i2p.router.transport.udp.UDPTransport)
            return ((net.i2p.router.transport.udp.UDPTransport) udp).getTuner();
        return null;
    }

    /**
     * Read a tuner property with a default.
     * Used by TuningHelper for display.
     */
    public static String getProp(net.i2p.router.RouterContext ctx,
                                 String param, String field, int defaultVal) {
        String key = PREFIX + param + "." + field.toLowerCase();
        return ctx.getProperty(key, String.valueOf(defaultVal));
    }

    // Override fields (set by form submission)
    private String _ackFrequencyOverride;
    private String _dataMessageTimeoutOverride;
    private String _obEstablishTimeOverride;
    private String _ibEstablishTimeOverride;
    private String _requeueTimeOverride;
    private String _replenishFrequencyOverride;
    private String _selectorLoopDelayOverride;
    private String _obMsgsPerPumpOverride;
    private String _ibMsgsPerPumpOverride;
    private String _initialWindowSizeOverride;
    private String _initialRTOOverride;
    private String _initialAckDelayOverride;
    private String _passiveFlushDelayOverride;
    private String _maxSlowStartWindowOverride;
    private String _writerQueueSizeOverride;
    private String _codelTargetOverride;
    private String _codelIntervalOverride;
    private String _westwoodDecayFactorOverride;
    private String _xdhPreCalcMinOverride;
    private String _ntcpThreadsOverride;
    private String _ntcpQueueCapacityOverride;
    private String _udpHandlerThreadsOverride;
    private String _peerOutboundQueueOverride;
    private String _transitThrottleFactorOverride;
    private String _throttleRejectExponentOverride;
    private String _maxParticipatingTunnelsOverride;
    private String _buildHandlerMaxQueueOverride;
    private String _goodDeficitThrottleOverride;
    private String _perTunnelBweDivisorOverride;
    private String _tunnelGrowthFactorOverride;
    private String _maxRTOOverride;
    private String _maxResendDelayOverride;
    private String _maxRetransmissionsOverride;
    private String _minResendDelayOverride;
    private String _congestionAvoidanceGrowthOverride;
    private String _slowStartGrowthOverride;
    private String _netDBSearchLimitOverride;
    private String _netDBMaxConcurrentOverride;
    private String _netDBSingleSearchTimeOverride;
    private String _maxConcurrentEstablishOverride;
    private String _maxProfilesOverride;
    private String _minFastPeersOverride;
    private String _buildRequestTimeoutOverride;
    private String _buildFirstHopTimeoutOverride;
    private String _edhPrecalcMinOverride;
    private String _mlkemPrecalcMinOverride;
}
