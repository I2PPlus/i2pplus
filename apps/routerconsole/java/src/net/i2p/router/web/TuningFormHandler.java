package net.i2p.router.web;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.udp.UDPTransport;

/**
 * Form handler for the transport tuning page.
 * Persists min/max/step ranges to autotune.config without restart.
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
    private String _edhPrecalcMinMin;
    private String _edhPrecalcMinMax;
    private String _edhPrecalcMinStep;
    private String _mlkemPrecalcMinMin;
    private String _mlkemPrecalcMinMax;
    private String _mlkemPrecalcMinStep;
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
    private String _threadsMin;
    private String _threadsMax;
    private String _threadsStep;
    private String _maxRTOMin;
    private String _maxRTOMax;
    private String _maxRTOStep;
    private String _maxResendDelayMin;
    private String _maxResendDelayMax;
    private String _maxResendDelayStep;
    private String _maxRetransmissionsMin;
    private String _maxRetransmissionsMax;
    private String _maxRetransmissionsStep;
    private String _maxRttMin;
    private String _maxRttMax;
    private String _maxRttStep;
    private String _initialResendDelayMin;
    private String _initialResendDelayMax;
    private String _initialResendDelayStep;
    private String _immediateAckDelayMin;
    private String _immediateAckDelayMax;
    private String _immediateAckDelayStep;
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
    private String _maxFastPeersMin;
    private String _maxFastPeersMax;
    private String _maxFastPeersStep;
    private String _minHighCapPeersMin;
    private String _minHighCapPeersMax;
    private String _minHighCapPeersStep;
    private String _maxHighCapPeersMin;
    private String _maxHighCapPeersMax;
    private String _maxHighCapPeersStep;
    private String _buildRequestTimeoutMin;
    private String _buildRequestTimeoutMax;
    private String _buildRequestTimeoutStep;
    private String _buildFirstHopTimeoutMin;
    private String _buildFirstHopTimeoutMax;
    private String _buildFirstHopTimeoutStep;

    // Default value fields (editable factory defaults)
    private String _ackFrequencyDefault;
    private String _dataMessageTimeoutDefault;
    private String _obEstablishTimeDefault;
    private String _ibEstablishTimeDefault;
    private String _requeueTimeDefault;
    private String _replenishFrequencyDefault;
    private String _selectorLoopDelayDefault;
    private String _obMsgsPerPumpDefault;
    private String _ibMsgsPerPumpDefault;
    private String _initialWindowSizeDefault;
    private String _initialRTODefault;
    private String _initialAckDelayDefault;
    private String _passiveFlushDelayDefault;
    private String _writerQueueSizeDefault;
    private String _codelTargetDefault;
    private String _codelIntervalDefault;
    private String _westwoodDecayFactorDefault;
    private String _maxSlowStartWindowDefault;
    private String _xdhPreCalcMinDefault;
    private String _ntcpThreadsDefault;
    private String _ntcpQueueCapacityDefault;
    private String _udpHandlerThreadsDefault;
    private String _peerOutboundQueueDefault;
    private String _transitThrottleFactorDefault;
    private String _throttleRejectExponentDefault;
    private String _maxParticipatingTunnelsDefault;
    private String _buildHandlerMaxQueueDefault;
    private String _goodDeficitThrottleDefault;
    private String _perTunnelBweDivisorDefault;
    private String _tunnelGrowthFactorDefault;
    private String _threadsDefault;
    private String _maxRTODefault;
    private String _maxResendDelayDefault;
    private String _maxRetransmissionsDefault;
    private String _netDBSearchLimitDefault;
    private String _netDBMaxConcurrentDefault;
    private String _netDBSingleSearchTimeDefault;
    private String _maxConcurrentEstablishDefault;
    private String _maxProfilesDefault;
    private String _minFastPeersDefault;
    private String _maxFastPeersDefault;
    private String _minHighCapPeersDefault;
    private String _maxHighCapPeersDefault;
    private String _buildRequestTimeoutDefault;
    private String _buildFirstHopTimeoutDefault;
    private String _minResendDelayDefault;
    private String _congestionAvoidanceGrowthDefault;
    private String _slowStartGrowthDefault;
    private String _maxRttDefault;
    private String _initialResendDelayDefault;
    private String _immediateAckDelayDefault;
    private String _edhPrecalcMinDefault;
    private String _mlkemPrecalcMinDefault;

    // setters - called by jsp:setProperty
    /**
     * Sets the minimum value for the ACK_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setAckFrequencyMin(String v) { _ackFrequencyMin = v; }
    /**
     * Sets the maximum value for the ACK_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setAckFrequencyMax(String v) { _ackFrequencyMax = v; }
    /**
     * Sets the step value for the ACK_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setAckFrequencyStep(String v) { _ackFrequencyStep = v; }
    /**
     * Sets the minimum value for the DATA_MESSAGE_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setDataMessageTimeoutMin(String v) { _dataMessageTimeoutMin = v; }
    /**
     * Sets the maximum value for the DATA_MESSAGE_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setDataMessageTimeoutMax(String v) { _dataMessageTimeoutMax = v; }
    /**
     * Sets the step value for the DATA_MESSAGE_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setDataMessageTimeoutStep(String v) { _dataMessageTimeoutStep = v; }
    /**
     * Sets the minimum value for the OB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObEstablishTimeMin(String v) { _obEstablishTimeMin = v; }
    /**
     * Sets the maximum value for the OB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObEstablishTimeMax(String v) { _obEstablishTimeMax = v; }
    /**
     * Sets the step value for the OB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObEstablishTimeStep(String v) { _obEstablishTimeStep = v; }
    /**
     * Sets the minimum value for the IB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbEstablishTimeMin(String v) { _ibEstablishTimeMin = v; }
    /**
     * Sets the maximum value for the IB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbEstablishTimeMax(String v) { _ibEstablishTimeMax = v; }
    /**
     * Sets the step value for the IB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbEstablishTimeStep(String v) { _ibEstablishTimeStep = v; }
    /**
     * Sets the minimum value for the REQUEUE_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setRequeueTimeMin(String v) { _requeueTimeMin = v; }
    /**
     * Sets the maximum value for the REQUEUE_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setRequeueTimeMax(String v) { _requeueTimeMax = v; }
    /**
     * Sets the step value for the REQUEUE_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setRequeueTimeStep(String v) { _requeueTimeStep = v; }
    /**
     * Sets the minimum value for the REPLENISH_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setReplenishFrequencyMin(String v) { _replenishFrequencyMin = v; }
    /**
     * Sets the maximum value for the REPLENISH_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setReplenishFrequencyMax(String v) { _replenishFrequencyMax = v; }
    /**
     * Sets the step value for the REPLENISH_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setReplenishFrequencyStep(String v) { _replenishFrequencyStep = v; }
    /**
     * Sets the minimum value for the SELECTOR_LOOP_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setSelectorLoopDelayMin(String v) { _selectorLoopDelayMin = v; }
    /**
     * Sets the maximum value for the SELECTOR_LOOP_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setSelectorLoopDelayMax(String v) { _selectorLoopDelayMax = v; }
    /**
     * Sets the step value for the SELECTOR_LOOP_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setSelectorLoopDelayStep(String v) { _selectorLoopDelayStep = v; }
    /**
     * Sets the minimum value for the OB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObMsgsPerPumpMin(String v) { _obMsgsPerPumpMin = v; }
    /**
     * Sets the maximum value for the OB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObMsgsPerPumpMax(String v) { _obMsgsPerPumpMax = v; }
    /**
     * Sets the step value for the OB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObMsgsPerPumpStep(String v) { _obMsgsPerPumpStep = v; }
    /**
     * Sets the minimum value for the IB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbMsgsPerPumpMin(String v) { _ibMsgsPerPumpMin = v; }
    /**
     * Sets the maximum value for the IB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbMsgsPerPumpMax(String v) { _ibMsgsPerPumpMax = v; }
    /**
     * Sets the step value for the IB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbMsgsPerPumpStep(String v) { _ibMsgsPerPumpStep = v; }
    /**
     * Sets the minimum value for the INITIAL_WINDOW_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialWindowSizeMin(String v) { _initialWindowSizeMin = v; }
    /**
     * Sets the maximum value for the INITIAL_WINDOW_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialWindowSizeMax(String v) { _initialWindowSizeMax = v; }
    /**
     * Sets the step value for the INITIAL_WINDOW_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialWindowSizeStep(String v) { _initialWindowSizeStep = v; }
    /**
     * Sets the minimum value for the INITIAL_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialRTOMin(String v) { _initialRTOMin = v; }
    /**
     * Sets the maximum value for the INITIAL_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialRTOMax(String v) { _initialRTOMax = v; }
    /**
     * Sets the step value for the INITIAL_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialRTOStep(String v) { _initialRTOStep = v; }
    /**
     * Sets the minimum value for the INITIAL_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialAckDelayMin(String v) { _initialAckDelayMin = v; }
    /**
     * Sets the maximum value for the INITIAL_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialAckDelayMax(String v) { _initialAckDelayMax = v; }
    /**
     * Sets the step value for the INITIAL_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialAckDelayStep(String v) { _initialAckDelayStep = v; }
    /**
     * Sets the minimum value for the PASSIVE_FLUSH_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPassiveFlushDelayMin(String v) { _passiveFlushDelayMin = v; }
    /**
     * Sets the maximum value for the PASSIVE_FLUSH_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPassiveFlushDelayMax(String v) { _passiveFlushDelayMax = v; }
    /**
     * Sets the step value for the PASSIVE_FLUSH_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPassiveFlushDelayStep(String v) { _passiveFlushDelayStep = v; }
    /**
     * Sets the minimum value for the WRITER_QUEUE_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWriterQueueSizeMin(String v) { _writerQueueSizeMin = v; }
    /**
     * Sets the maximum value for the WRITER_QUEUE_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWriterQueueSizeMax(String v) { _writerQueueSizeMax = v; }
    /**
     * Sets the step value for the WRITER_QUEUE_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWriterQueueSizeStep(String v) { _writerQueueSizeStep = v; }
    /**
     * Sets the minimum value for the CODEL_TARGET tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelTargetMin(String v) { _codelTargetMin = v; }
    /**
     * Sets the maximum value for the CODEL_TARGET tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelTargetMax(String v) { _codelTargetMax = v; }
    /**
     * Sets the step value for the CODEL_TARGET tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelTargetStep(String v) { _codelTargetStep = v; }
    /**
     * Sets the minimum value for the CODEL_INTERVAL tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelIntervalMin(String v) { _codelIntervalMin = v; }
    /**
     * Sets the maximum value for the CODEL_INTERVAL tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelIntervalMax(String v) { _codelIntervalMax = v; }
    /**
     * Sets the step value for the CODEL_INTERVAL tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelIntervalStep(String v) { _codelIntervalStep = v; }
    /**
     * Sets the minimum value for the WESTWOOD_DECAY_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWestwoodDecayFactorMin(String v) { _westwoodDecayFactorMin = v; }
    /**
     * Sets the maximum value for the WESTWOOD_DECAY_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWestwoodDecayFactorMax(String v) { _westwoodDecayFactorMax = v; }
    /**
     * Sets the step value for the WESTWOOD_DECAY_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWestwoodDecayFactorStep(String v) { _westwoodDecayFactorStep = v; }
    /**
     * Sets the minimum value for the MAX_SLOW_START_WINDOW tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxSlowStartWindowMin(String v) { _maxSlowStartWindowMin = v; }
    /**
     * Sets the maximum value for the MAX_SLOW_START_WINDOW tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxSlowStartWindowMax(String v) { _maxSlowStartWindowMax = v; }
    /**
     * Sets the step value for the MAX_SLOW_START_WINDOW tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxSlowStartWindowStep(String v) { _maxSlowStartWindowStep = v; }
    /**
     * Sets the minimum value for the XDH_PRE_CALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setXdhPreCalcMinMin(String v) { _xdhPreCalcMinMin = v; }
    /**
     * Sets the maximum value for the XDH_PRE_CALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setXdhPreCalcMinMax(String v) { _xdhPreCalcMinMax = v; }
    /**
     * Sets the step value for the XDH_PRE_CALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setXdhPreCalcMinStep(String v) { _xdhPreCalcMinStep = v; }
    /**
     * Sets the minimum value for the EDH_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setEdhPrecalcMinMin(String v) { _edhPrecalcMinMin = v; }
    /**
     * Sets the maximum value for the EDH_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setEdhPrecalcMinMax(String v) { _edhPrecalcMinMax = v; }
    /**
     * Sets the step value for the EDH_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setEdhPrecalcMinStep(String v) { _edhPrecalcMinStep = v; }
    /**
     * Sets the minimum value for the MLKEM_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMlkemPrecalcMinMin(String v) { _mlkemPrecalcMinMin = v; }
    /**
     * Sets the maximum value for the MLKEM_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMlkemPrecalcMinMax(String v) { _mlkemPrecalcMinMax = v; }
    /**
     * Sets the step value for the MLKEM_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMlkemPrecalcMinStep(String v) { _mlkemPrecalcMinStep = v; }
    /**
     * Sets the minimum value for the NTCP_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpThreadsMin(String v) { _ntcpThreadsMin = v; }
    /**
     * Sets the maximum value for the NTCP_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpThreadsMax(String v) { _ntcpThreadsMax = v; }
    /**
     * Sets the step value for the NTCP_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpThreadsStep(String v) { _ntcpThreadsStep = v; }
    /**
     * Sets the minimum value for the NTCP_QUEUE_CAPACITY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpQueueCapacityMin(String v) { _ntcpQueueCapacityMin = v; }
    /**
     * Sets the maximum value for the NTCP_QUEUE_CAPACITY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpQueueCapacityMax(String v) { _ntcpQueueCapacityMax = v; }
    /**
     * Sets the step value for the NTCP_QUEUE_CAPACITY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpQueueCapacityStep(String v) { _ntcpQueueCapacityStep = v; }
    /**
     * Sets the minimum value for the UDP_HANDLER_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setUdpHandlerThreadsMin(String v) { _udpHandlerThreadsMin = v; }
    /**
     * Sets the maximum value for the UDP_HANDLER_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setUdpHandlerThreadsMax(String v) { _udpHandlerThreadsMax = v; }
    /**
     * Sets the step value for the UDP_HANDLER_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setUdpHandlerThreadsStep(String v) { _udpHandlerThreadsStep = v; }
    /**
     * Sets the minimum value for the PEER_OUTBOUND_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPeerOutboundQueueMin(String v) { _peerOutboundQueueMin = v; }
    /**
     * Sets the maximum value for the PEER_OUTBOUND_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPeerOutboundQueueMax(String v) { _peerOutboundQueueMax = v; }
    /**
     * Sets the step value for the PEER_OUTBOUND_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPeerOutboundQueueStep(String v) { _peerOutboundQueueStep = v; }
    /**
     * Sets the minimum value for the TRANSIT_THROTTLE_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTransitThrottleFactorMin(String v) { _transitThrottleFactorMin = v; }
    /**
     * Sets the maximum value for the TRANSIT_THROTTLE_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTransitThrottleFactorMax(String v) { _transitThrottleFactorMax = v; }
    /**
     * Sets the step value for the TRANSIT_THROTTLE_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTransitThrottleFactorStep(String v) { _transitThrottleFactorStep = v; }
    /**
     * Sets the minimum value for the THROTTLE_REJECT_EXPONENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThrottleRejectExponentMin(String v) { _throttleRejectExponentMin = v; }
    /**
     * Sets the maximum value for the THROTTLE_REJECT_EXPONENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThrottleRejectExponentMax(String v) { _throttleRejectExponentMax = v; }
    /**
     * Sets the step value for the THROTTLE_REJECT_EXPONENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThrottleRejectExponentStep(String v) { _throttleRejectExponentStep = v; }
    /**
     * Sets the minimum value for the MAX_PARTICIPATING_TUNNELS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxParticipatingTunnelsMin(String v) { _maxParticipatingTunnelsMin = v; }
    /**
     * Sets the maximum value for the MAX_PARTICIPATING_TUNNELS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxParticipatingTunnelsMax(String v) { _maxParticipatingTunnelsMax = v; }
    /**
     * Sets the step value for the MAX_PARTICIPATING_TUNNELS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxParticipatingTunnelsStep(String v) { _maxParticipatingTunnelsStep = v; }
    /**
     * Sets the minimum value for the BUILD_HANDLER_MAX_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildHandlerMaxQueueMin(String v) { _buildHandlerMaxQueueMin = v; }
    /**
     * Sets the maximum value for the BUILD_HANDLER_MAX_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildHandlerMaxQueueMax(String v) { _buildHandlerMaxQueueMax = v; }
    /**
     * Sets the step value for the BUILD_HANDLER_MAX_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildHandlerMaxQueueStep(String v) { _buildHandlerMaxQueueStep = v; }
    /**
     * Sets the minimum value for the GOOD_DEFICIT_THROTTLE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setGoodDeficitThrottleMin(String v) { _goodDeficitThrottleMin = v; }
    /**
     * Sets the maximum value for the GOOD_DEFICIT_THROTTLE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setGoodDeficitThrottleMax(String v) { _goodDeficitThrottleMax = v; }
    /**
     * Sets the step value for the GOOD_DEFICIT_THROTTLE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setGoodDeficitThrottleStep(String v) { _goodDeficitThrottleStep = v; }
    /**
     * Sets the minimum value for the PER_TUNNEL_BWE_DIVISOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPerTunnelBweDivisorMin(String v) { _perTunnelBweDivisorMin = v; }
    /**
     * Sets the maximum value for the PER_TUNNEL_BWE_DIVISOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPerTunnelBweDivisorMax(String v) { _perTunnelBweDivisorMax = v; }
    /**
     * Sets the step value for the PER_TUNNEL_BWE_DIVISOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPerTunnelBweDivisorStep(String v) { _perTunnelBweDivisorStep = v; }
    /**
     * Sets the minimum value for the TUNNEL_GROWTH_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTunnelGrowthFactorMin(String v) { _tunnelGrowthFactorMin = v; }
    /**
     * Sets the maximum value for the TUNNEL_GROWTH_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTunnelGrowthFactorMax(String v) { _tunnelGrowthFactorMax = v; }
    /**
     * Sets the step value for the TUNNEL_GROWTH_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTunnelGrowthFactorStep(String v) { _tunnelGrowthFactorStep = v; }
    /**
     * Sets the minimum value for the THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThreadsMin(String v) { _threadsMin = v; }
    /**
     * Sets the maximum value for the THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThreadsMax(String v) { _threadsMax = v; }
    /**
     * Sets the step value for the THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThreadsStep(String v) { _threadsStep = v; }
    /**
     * Sets the minimum value for the MAX_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRTOMin(String v) { _maxRTOMin = v; }
    /**
     * Sets the maximum value for the MAX_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRTOMax(String v) { _maxRTOMax = v; }
    /**
     * Sets the step value for the MAX_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRTOStep(String v) { _maxRTOStep = v; }
    /**
     * Sets the minimum value for the MAX_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxResendDelayMin(String v) { _maxResendDelayMin = v; }
    /**
     * Sets the maximum value for the MAX_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxResendDelayMax(String v) { _maxResendDelayMax = v; }
    /**
     * Sets the step value for the MAX_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxResendDelayStep(String v) { _maxResendDelayStep = v; }
    /**
     * Sets the minimum value for the MAX_RETRANSMISSIONS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRetransmissionsMin(String v) { _maxRetransmissionsMin = v; }
    /**
     * Sets the maximum value for the MAX_RETRANSMISSIONS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRetransmissionsMax(String v) { _maxRetransmissionsMax = v; }
    /**
     * Sets the step value for the MAX_RETRANSMISSIONS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRetransmissionsStep(String v) { _maxRetransmissionsStep = v; }
    /**
     * Sets the minimum value for the MAX_RTT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRttMin(String v) { _maxRttMin = v; }
    /**
     * Sets the maximum value for the MAX_RTT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRttMax(String v) { _maxRttMax = v; }
    /**
     * Sets the step value for the MAX_RTT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRttStep(String v) { _maxRttStep = v; }
    /**
     * Sets the minimum value for the INITIAL_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialResendDelayMin(String v) { _initialResendDelayMin = v; }
    /**
     * Sets the maximum value for the INITIAL_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialResendDelayMax(String v) { _initialResendDelayMax = v; }
    /**
     * Sets the step value for the INITIAL_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialResendDelayStep(String v) { _initialResendDelayStep = v; }
    /**
     * Sets the minimum value for the IMMEDIATE_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setImmediateAckDelayMin(String v) { _immediateAckDelayMin = v; }
    /**
     * Sets the maximum value for the IMMEDIATE_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setImmediateAckDelayMax(String v) { _immediateAckDelayMax = v; }
    /**
     * Sets the step value for the IMMEDIATE_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setImmediateAckDelayStep(String v) { _immediateAckDelayStep = v; }
    /**
     * Sets the minimum value for the NET_DBSEARCH_LIMIT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSearchLimitMin(String v) { _netDBSearchLimitMin = v; }
    /**
     * Sets the maximum value for the NET_DBSEARCH_LIMIT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSearchLimitMax(String v) { _netDBSearchLimitMax = v; }
    /**
     * Sets the step value for the NET_DBSEARCH_LIMIT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSearchLimitStep(String v) { _netDBSearchLimitStep = v; }
    /**
     * Sets the minimum value for the NET_DBMAX_CONCURRENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBMaxConcurrentMin(String v) { _netDBMaxConcurrentMin = v; }
    /**
     * Sets the maximum value for the NET_DBMAX_CONCURRENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBMaxConcurrentMax(String v) { _netDBMaxConcurrentMax = v; }
    /**
     * Sets the step value for the NET_DBMAX_CONCURRENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBMaxConcurrentStep(String v) { _netDBMaxConcurrentStep = v; }
    /**
     * Sets the minimum value for the NET_DBSINGLE_SEARCH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSingleSearchTimeMin(String v) { _netDBSingleSearchTimeMin = v; }
    /**
     * Sets the maximum value for the NET_DBSINGLE_SEARCH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSingleSearchTimeMax(String v) { _netDBSingleSearchTimeMax = v; }
    /**
     * Sets the step value for the NET_DBSINGLE_SEARCH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSingleSearchTimeStep(String v) { _netDBSingleSearchTimeStep = v; }
    /**
     * Sets the minimum value for the MAX_CONCURRENT_ESTABLISH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxConcurrentEstablishMin(String v) { _maxConcurrentEstablishMin = v; }
    /**
     * Sets the maximum value for the MAX_CONCURRENT_ESTABLISH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxConcurrentEstablishMax(String v) { _maxConcurrentEstablishMax = v; }
    /**
     * Sets the step value for the MAX_CONCURRENT_ESTABLISH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxConcurrentEstablishStep(String v) { _maxConcurrentEstablishStep = v; }
    /**
     * Sets the minimum value for the MAX_PROFILES tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxProfilesMin(String v) { _maxProfilesMin = v; }
    /**
     * Sets the maximum value for the MAX_PROFILES tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxProfilesMax(String v) { _maxProfilesMax = v; }
    /**
     * Sets the step value for the MAX_PROFILES tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxProfilesStep(String v) { _maxProfilesStep = v; }
    /**
     * Sets the minimum value for the MIN_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinFastPeersMin(String v) { _minFastPeersMin = v; }
    /**
     * Sets the maximum value for the MIN_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinFastPeersMax(String v) { _minFastPeersMax = v; }
    /**
     * Sets the step value for the MIN_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinFastPeersStep(String v) { _minFastPeersStep = v; }
    /**
     * Sets the minimum value for the MAX_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxFastPeersMin(String v) { _maxFastPeersMin = v; }
    /**
     * Sets the maximum value for the MAX_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxFastPeersMax(String v) { _maxFastPeersMax = v; }
    /**
     * Sets the step value for the MAX_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxFastPeersStep(String v) { _maxFastPeersStep = v; }
    /**
     * Sets the minimum value for the MIN_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinHighCapPeersMin(String v) { _minHighCapPeersMin = v; }
    /**
     * Sets the maximum value for the MIN_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinHighCapPeersMax(String v) { _minHighCapPeersMax = v; }
    /**
     * Sets the step value for the MIN_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinHighCapPeersStep(String v) { _minHighCapPeersStep = v; }
    /**
     * Sets the minimum value for the MAX_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxHighCapPeersMin(String v) { _maxHighCapPeersMin = v; }
    /**
     * Sets the maximum value for the MAX_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxHighCapPeersMax(String v) { _maxHighCapPeersMax = v; }
    /**
     * Sets the step value for the MAX_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxHighCapPeersStep(String v) { _maxHighCapPeersStep = v; }
    /**
     * Sets the minimum value for the BUILD_REQUEST_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildRequestTimeoutMin(String v) { _buildRequestTimeoutMin = v; }
    /**
     * Sets the maximum value for the BUILD_REQUEST_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildRequestTimeoutMax(String v) { _buildRequestTimeoutMax = v; }
    /**
     * Sets the step value for the BUILD_REQUEST_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildRequestTimeoutStep(String v) { _buildRequestTimeoutStep = v; }
    /**
     * Sets the minimum value for the BUILD_FIRST_HOP_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildFirstHopTimeoutMin(String v) { _buildFirstHopTimeoutMin = v; }
    /**
     * Sets the maximum value for the BUILD_FIRST_HOP_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildFirstHopTimeoutMax(String v) { _buildFirstHopTimeoutMax = v; }
    /**
     * Sets the step value for the BUILD_FIRST_HOP_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildFirstHopTimeoutStep(String v) { _buildFirstHopTimeoutStep = v; }

    // Default value setters
    /**
     * Sets the factory-default value for the ACK_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setAckFrequencyDefault(String v) { _ackFrequencyDefault = v; }
    /**
     * Sets the factory-default value for the DATA_MESSAGE_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setDataMessageTimeoutDefault(String v) { _dataMessageTimeoutDefault = v; }
    /**
     * Sets the factory-default value for the OB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObEstablishTimeDefault(String v) { _obEstablishTimeDefault = v; }
    /**
     * Sets the factory-default value for the IB_ESTABLISH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbEstablishTimeDefault(String v) { _ibEstablishTimeDefault = v; }
    /**
     * Sets the factory-default value for the REQUEUE_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setRequeueTimeDefault(String v) { _requeueTimeDefault = v; }
    /**
     * Sets the factory-default value for the REPLENISH_FREQUENCY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setReplenishFrequencyDefault(String v) { _replenishFrequencyDefault = v; }
    /**
     * Sets the factory-default value for the SELECTOR_LOOP_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setSelectorLoopDelayDefault(String v) { _selectorLoopDelayDefault = v; }
    /**
     * Sets the factory-default value for the OB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setObMsgsPerPumpDefault(String v) { _obMsgsPerPumpDefault = v; }
    /**
     * Sets the factory-default value for the IB_MSGS_PER_PUMP tuning parameter.
     *
     * @param v the string value to set
     */
    public void setIbMsgsPerPumpDefault(String v) { _ibMsgsPerPumpDefault = v; }
    /**
     * Sets the factory-default value for the INITIAL_WINDOW_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialWindowSizeDefault(String v) { _initialWindowSizeDefault = v; }
    /**
     * Sets the factory-default value for the INITIAL_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialRTODefault(String v) { _initialRTODefault = v; }
    /**
     * Sets the factory-default value for the INITIAL_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialAckDelayDefault(String v) { _initialAckDelayDefault = v; }
    /**
     * Sets the factory-default value for the PASSIVE_FLUSH_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPassiveFlushDelayDefault(String v) { _passiveFlushDelayDefault = v; }
    /**
     * Sets the factory-default value for the WRITER_QUEUE_SIZE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWriterQueueSizeDefault(String v) { _writerQueueSizeDefault = v; }
    /**
     * Sets the factory-default value for the CODEL_TARGET tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelTargetDefault(String v) { _codelTargetDefault = v; }
    /**
     * Sets the factory-default value for the CODEL_INTERVAL tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCodelIntervalDefault(String v) { _codelIntervalDefault = v; }
    /**
     * Sets the factory-default value for the WESTWOOD_DECAY_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setWestwoodDecayFactorDefault(String v) { _westwoodDecayFactorDefault = v; }
    /**
     * Sets the factory-default value for the MAX_SLOW_START_WINDOW tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxSlowStartWindowDefault(String v) { _maxSlowStartWindowDefault = v; }
    /**
     * Sets the factory-default value for the XDH_PRE_CALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setXdhPreCalcMinDefault(String v) { _xdhPreCalcMinDefault = v; }
    /**
     * Sets the factory-default value for the NTCP_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpThreadsDefault(String v) { _ntcpThreadsDefault = v; }
    /**
     * Sets the factory-default value for the NTCP_QUEUE_CAPACITY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpQueueCapacityDefault(String v) { _ntcpQueueCapacityDefault = v; }
    /**
     * Sets the factory-default value for the UDP_HANDLER_THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setUdpHandlerThreadsDefault(String v) { _udpHandlerThreadsDefault = v; }
    /**
     * Sets the factory-default value for the PEER_OUTBOUND_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPeerOutboundQueueDefault(String v) { _peerOutboundQueueDefault = v; }
    /**
     * Sets the factory-default value for the TRANSIT_THROTTLE_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTransitThrottleFactorDefault(String v) { _transitThrottleFactorDefault = v; }
    /**
     * Sets the factory-default value for the THROTTLE_REJECT_EXPONENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThrottleRejectExponentDefault(String v) { _throttleRejectExponentDefault = v; }
    /**
     * Sets the factory-default value for the MAX_PARTICIPATING_TUNNELS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxParticipatingTunnelsDefault(String v) { _maxParticipatingTunnelsDefault = v; }
    /**
     * Sets the factory-default value for the BUILD_HANDLER_MAX_QUEUE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildHandlerMaxQueueDefault(String v) { _buildHandlerMaxQueueDefault = v; }
    /**
     * Sets the factory-default value for the GOOD_DEFICIT_THROTTLE tuning parameter.
     *
     * @param v the string value to set
     */
    public void setGoodDeficitThrottleDefault(String v) { _goodDeficitThrottleDefault = v; }
    /**
     * Sets the factory-default value for the PER_TUNNEL_BWE_DIVISOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setPerTunnelBweDivisorDefault(String v) { _perTunnelBweDivisorDefault = v; }
    /**
     * Sets the factory-default value for the TUNNEL_GROWTH_FACTOR tuning parameter.
     *
     * @param v the string value to set
     */
    public void setTunnelGrowthFactorDefault(String v) { _tunnelGrowthFactorDefault = v; }
    /**
     * Sets the factory-default value for the THREADS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setThreadsDefault(String v) { _threadsDefault = v; }
    /**
     * Sets the factory-default value for the MAX_RTO tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRTODefault(String v) { _maxRTODefault = v; }
    /**
     * Sets the factory-default value for the MAX_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxResendDelayDefault(String v) { _maxResendDelayDefault = v; }
    /**
     * Sets the factory-default value for the MAX_RETRANSMISSIONS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRetransmissionsDefault(String v) { _maxRetransmissionsDefault = v; }
    /**
     * Sets the factory-default value for the NET_DBSEARCH_LIMIT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSearchLimitDefault(String v) { _netDBSearchLimitDefault = v; }
    /**
     * Sets the factory-default value for the NET_DBMAX_CONCURRENT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBMaxConcurrentDefault(String v) { _netDBMaxConcurrentDefault = v; }
    /**
     * Sets the factory-default value for the NET_DBSINGLE_SEARCH_TIME tuning parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSingleSearchTimeDefault(String v) { _netDBSingleSearchTimeDefault = v; }
    /**
     * Sets the factory-default value for the MAX_CONCURRENT_ESTABLISH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxConcurrentEstablishDefault(String v) { _maxConcurrentEstablishDefault = v; }
    /**
     * Sets the factory-default value for the MAX_PROFILES tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxProfilesDefault(String v) { _maxProfilesDefault = v; }
    /**
     * Sets the factory-default value for the MIN_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinFastPeersDefault(String v) { _minFastPeersDefault = v; }
    /**
     * Sets the factory-default value for the MAX_FAST_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxFastPeersDefault(String v) { _maxFastPeersDefault = v; }
    /**
     * Sets the factory-default value for the MIN_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinHighCapPeersDefault(String v) { _minHighCapPeersDefault = v; }
    /**
     * Sets the factory-default value for the MAX_HIGH_CAP_PEERS tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxHighCapPeersDefault(String v) { _maxHighCapPeersDefault = v; }
    /**
     * Sets the factory-default value for the BUILD_REQUEST_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildRequestTimeoutDefault(String v) { _buildRequestTimeoutDefault = v; }
    /**
     * Sets the factory-default value for the BUILD_FIRST_HOP_TIMEOUT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setBuildFirstHopTimeoutDefault(String v) { _buildFirstHopTimeoutDefault = v; }
    /**
     * Sets the factory-default value for the MIN_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMinResendDelayDefault(String v) { _minResendDelayDefault = v; }
    /**
     * Sets the factory-default value for the CONGESTION_AVOIDANCE_GROWTH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setCongestionAvoidanceGrowthDefault(String v) { _congestionAvoidanceGrowthDefault = v; }
    /**
     * Sets the factory-default value for the SLOW_START_GROWTH tuning parameter.
     *
     * @param v the string value to set
     */
    public void setSlowStartGrowthDefault(String v) { _slowStartGrowthDefault = v; }
    /**
     * Sets the factory-default value for the MAX_RTT tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRttDefault(String v) { _maxRttDefault = v; }
    /**
     * Sets the factory-default value for the INITIAL_RESEND_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setInitialResendDelayDefault(String v) { _initialResendDelayDefault = v; }
    /**
     * Sets the factory-default value for the IMMEDIATE_ACK_DELAY tuning parameter.
     *
     * @param v the string value to set
     */
    public void setImmediateAckDelayDefault(String v) { _immediateAckDelayDefault = v; }
    /**
     * Sets the factory-default value for the EDH_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setEdhPrecalcMinDefault(String v) { _edhPrecalcMinDefault = v; }
    /**
     * Sets the factory-default value for the MLKEM_PRECALC_MIN tuning parameter.
     *
     * @param v the string value to set
     */
    public void setMlkemPrecalcMinDefault(String v) { _mlkemPrecalcMinDefault = v; }

    // Auto-tuning override setters (checkbox: -1 = auto, >= 0 = manual lock)
    /**
     * Sets the override control for the ACK_FREQUENCY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setAckFrequencyOverride(String v) { _ackFrequencyOverride = v; }
    /**
     * Sets the override control for the DATA_MESSAGE_TIMEOUT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setDataMessageTimeoutOverride(String v) { _dataMessageTimeoutOverride = v; }
    /**
     * Sets the override control for the OB_ESTABLISH_TIME tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setObEstablishTimeOverride(String v) { _obEstablishTimeOverride = v; }
    /**
     * Sets the override control for the IB_ESTABLISH_TIME tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setIbEstablishTimeOverride(String v) { _ibEstablishTimeOverride = v; }
    /**
     * Sets the override control for the REQUEUE_TIME tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setRequeueTimeOverride(String v) { _requeueTimeOverride = v; }
    /**
     * Sets the override control for the REPLENISH_FREQUENCY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setReplenishFrequencyOverride(String v) { _replenishFrequencyOverride = v; }
    /**
     * Sets the override control for the SELECTOR_LOOP_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setSelectorLoopDelayOverride(String v) { _selectorLoopDelayOverride = v; }
    /**
     * Sets the override control for the OB_MSGS_PER_PUMP tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setObMsgsPerPumpOverride(String v) { _obMsgsPerPumpOverride = v; }
    /**
     * Sets the override control for the IB_MSGS_PER_PUMP tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setIbMsgsPerPumpOverride(String v) { _ibMsgsPerPumpOverride = v; }
    /**
     * Sets the override control for the INITIAL_WINDOW_SIZE tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setInitialWindowSizeOverride(String v) { _initialWindowSizeOverride = v; }
    /**
     * Sets the override control for the INITIAL_RTO tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setInitialRTOOverride(String v) { _initialRTOOverride = v; }
    /**
     * Sets the override control for the INITIAL_ACK_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setInitialAckDelayOverride(String v) { _initialAckDelayOverride = v; }
    /**
     * Sets the override control for the PASSIVE_FLUSH_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setPassiveFlushDelayOverride(String v) { _passiveFlushDelayOverride = v; }
    /**
     * Sets the override control for the MAX_SLOW_START_WINDOW tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxSlowStartWindowOverride(String v) { _maxSlowStartWindowOverride = v; }
    /**
     * Sets the override control for the WRITER_QUEUE_SIZE tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setWriterQueueSizeOverride(String v) { _writerQueueSizeOverride = v; }
    /**
     * Sets the override control for the CODEL_TARGET tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setCodelTargetOverride(String v) { _codelTargetOverride = v; }
    /**
     * Sets the override control for the CODEL_INTERVAL tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setCodelIntervalOverride(String v) { _codelIntervalOverride = v; }
    /**
     * Sets the override control for the WESTWOOD_DECAY_FACTOR tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setWestwoodDecayFactorOverride(String v) { _westwoodDecayFactorOverride = v; }
    /**
     * Sets the override control for the XDH_PRE_CALC_MIN tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setXdhPreCalcMinOverride(String v) { _xdhPreCalcMinOverride = v; }
    /**
     * Sets the override control for the NTCP_THREADS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpThreadsOverride(String v) { _ntcpThreadsOverride = v; }
    /**
     * Sets the override control for the NTCP_QUEUE_CAPACITY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setNtcpQueueCapacityOverride(String v) { _ntcpQueueCapacityOverride = v; }
    /**
     * Sets the override control for the UDP_HANDLER_THREADS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setUdpHandlerThreadsOverride(String v) { _udpHandlerThreadsOverride = v; }
    /**
     * Sets the override control for the PEER_OUTBOUND_QUEUE tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setPeerOutboundQueueOverride(String v) { _peerOutboundQueueOverride = v; }
    /**
     * Sets the override control for the TRANSIT_THROTTLE_FACTOR tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setTransitThrottleFactorOverride(String v) { _transitThrottleFactorOverride = v; }
    /**
     * Sets the override control for the THROTTLE_REJECT_EXPONENT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setThrottleRejectExponentOverride(String v) { _throttleRejectExponentOverride = v; }
    /**
     * Sets the override control for the MAX_PARTICIPATING_TUNNELS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxParticipatingTunnelsOverride(String v) { _maxParticipatingTunnelsOverride = v; }
    /**
     * Sets the override control for the BUILD_HANDLER_MAX_QUEUE tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setBuildHandlerMaxQueueOverride(String v) { _buildHandlerMaxQueueOverride = v; }
    /**
     * Sets the override control for the GOOD_DEFICIT_THROTTLE tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setGoodDeficitThrottleOverride(String v) { _goodDeficitThrottleOverride = v; }
    /**
     * Sets the override control for the PER_TUNNEL_BWE_DIVISOR tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setPerTunnelBweDivisorOverride(String v) { _perTunnelBweDivisorOverride = v; }
    /**
     * Sets the override control for the TUNNEL_GROWTH_FACTOR tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setTunnelGrowthFactorOverride(String v) { _tunnelGrowthFactorOverride = v; }
    /**
     * Sets the override control for the THREADS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setThreadsOverride(String v) { _threadsOverride = v; }
    /**
     * Sets the override control for the MAX_RTO tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRTOOverride(String v) { _maxRTOOverride = v; }
    /**
     * Sets the override control for the MAX_RESEND_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxResendDelayOverride(String v) { _maxResendDelayOverride = v; }
    /**
     * Sets the override control for the MAX_RETRANSMISSIONS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRetransmissionsOverride(String v) { _maxRetransmissionsOverride = v; }
    /**
     * Sets the override control for the MAX_RTT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxRttOverride(String v) { _maxRttOverride = v; }
    /**
     * Sets the override control for the INITIAL_RESEND_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setInitialResendDelayOverride(String v) { _initialResendDelayOverride = v; }
    /**
     * Sets the override control for the IMMEDIATE_ACK_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setImmediateAckDelayOverride(String v) { _immediateAckDelayOverride = v; }
    /**
     * Sets the override control for the MIN_RESEND_DELAY tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMinResendDelayOverride(String v) { _minResendDelayOverride = v; }
    /**
     * Sets the override control for the CONGESTION_AVOIDANCE_GROWTH tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setCongestionAvoidanceGrowthOverride(String v) { _congestionAvoidanceGrowthOverride = v; }
    /**
     * Sets the override control for the SLOW_START_GROWTH tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setSlowStartGrowthOverride(String v) { _slowStartGrowthOverride = v; }
    /**
     * Sets the override control for the NET_DBSEARCH_LIMIT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSearchLimitOverride(String v) { _netDBSearchLimitOverride = v; }
    /**
     * Sets the override control for the NET_DBMAX_CONCURRENT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBMaxConcurrentOverride(String v) { _netDBMaxConcurrentOverride = v; }
    /**
     * Sets the override control for the NET_DBSINGLE_SEARCH_TIME tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setNetDBSingleSearchTimeOverride(String v) { _netDBSingleSearchTimeOverride = v; }
    /**
     * Sets the override control for the MAX_CONCURRENT_ESTABLISH tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxConcurrentEstablishOverride(String v) { _maxConcurrentEstablishOverride = v; }
    /**
     * Sets the override control for the MAX_PROFILES tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMaxProfilesOverride(String v) { _maxProfilesOverride = v; }
    /**
     * Sets the override control for the MIN_FAST_PEERS tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setMinFastPeersOverride(String v) { _minFastPeersOverride = v; }
    /**
     * Sets the override control for the BUILD_REQUEST_TIMEOUT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setBuildRequestTimeoutOverride(String v) { _buildRequestTimeoutOverride = v; }
    /**
     * Sets the override control for the BUILD_FIRST_HOP_TIMEOUT tuning parameter. -1 enables auto-tuning, any non-negative value locks the parameter.
     *
     * @param v the string value to set
     */
    public void setBuildFirstHopTimeoutOverride(String v) { _buildFirstHopTimeoutOverride = v; }

    @Override
    protected void processForm() {
        if (_action == null)
            return;

        // Restore Defaults: reset all params to factory defaults
        if (_action.equals(_t("Restore Defaults"))) {
            Tuner tuner = getTuner();
            if (tuner != null) {
                tuner.restoreDefaults();
                addFormNotice(_t("All parameters restored to factory defaults"));
            } else {
                addFormNotice(_t("Auto-Tuning is not available"));
            }
            return;
        }

        if (!_action.equals(_t("Save")))
            return;

        Map<String, String> changes = new HashMap<>();

        // Transport
        saveField(changes, "ACK_FREQUENCY", "Min", _ackFrequencyMin);
        saveField(changes, "ACK_FREQUENCY", "Max", _ackFrequencyMax);
        saveField(changes, "ACK_FREQUENCY", "Step", _ackFrequencyStep);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Min", _dataMessageTimeoutMin);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Max", _dataMessageTimeoutMax);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Step", _dataMessageTimeoutStep);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Min", _obEstablishTimeMin);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Max", _obEstablishTimeMax);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Step", _obEstablishTimeStep);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Min", _ibEstablishTimeMin);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Max", _ibEstablishTimeMax);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Step", _ibEstablishTimeStep);

        // Tunnel
        saveField(changes, "REQUEUE_TIME", "Min", _requeueTimeMin);
        saveField(changes, "REQUEUE_TIME", "Max", _requeueTimeMax);
        saveField(changes, "REQUEUE_TIME", "Step", _requeueTimeStep);
        saveField(changes, "REPLENISH_FREQUENCY", "Min", _replenishFrequencyMin);
        saveField(changes, "REPLENISH_FREQUENCY", "Max", _replenishFrequencyMax);
        saveField(changes, "REPLENISH_FREQUENCY", "Step", _replenishFrequencyStep);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Min", _selectorLoopDelayMin);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Max", _selectorLoopDelayMax);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Step", _selectorLoopDelayStep);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Min", _obMsgsPerPumpMin);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Max", _obMsgsPerPumpMax);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Step", _obMsgsPerPumpStep);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Min", _ibMsgsPerPumpMin);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Max", _ibMsgsPerPumpMax);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Step", _ibMsgsPerPumpStep);

        // Streaming
        saveField(changes, "INITIAL_WINDOW_SIZE", "Min", _initialWindowSizeMin);
        saveField(changes, "INITIAL_WINDOW_SIZE", "Max", _initialWindowSizeMax);
        saveField(changes, "INITIAL_WINDOW_SIZE", "Step", _initialWindowSizeStep);
        saveField(changes, "INITIAL_RTO", "Min", _initialRTOMin);
        saveField(changes, "INITIAL_RTO", "Max", _initialRTOMax);
        saveField(changes, "INITIAL_RTO", "Step", _initialRTOStep);
        saveField(changes, "INITIAL_ACK_DELAY", "Min", _initialAckDelayMin);
        saveField(changes, "INITIAL_ACK_DELAY", "Max", _initialAckDelayMax);
        saveField(changes, "INITIAL_ACK_DELAY", "Step", _initialAckDelayStep);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Min", _passiveFlushDelayMin);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Max", _passiveFlushDelayMax);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Step", _passiveFlushDelayStep);

        // I2CP
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Min", _writerQueueSizeMin);
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Max", _writerQueueSizeMax);
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Step", _writerQueueSizeStep);

        // CoDel
        saveField(changes, "CODEL_TARGET", "Min", _codelTargetMin);
        saveField(changes, "CODEL_TARGET", "Max", _codelTargetMax);
        saveField(changes, "CODEL_TARGET", "Step", _codelTargetStep);
        saveField(changes, "CODEL_INTERVAL", "Min", _codelIntervalMin);
        saveField(changes, "CODEL_INTERVAL", "Max", _codelIntervalMax);
        saveField(changes, "CODEL_INTERVAL", "Step", _codelIntervalStep);

        // Westwood
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Min", _westwoodDecayFactorMin);
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Max", _westwoodDecayFactorMax);
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Step", _westwoodDecayFactorStep);

        // Streaming (continued)
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Min", _maxSlowStartWindowMin);
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Max", _maxSlowStartWindowMax);
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Step", _maxSlowStartWindowStep);

        // Buffers & Threads
        saveField(changes, "crypto.x25519.precalcMin", "Min", _xdhPreCalcMinMin);
        saveField(changes, "crypto.x25519.precalcMin", "Max", _xdhPreCalcMinMax);
        saveField(changes, "crypto.x25519.precalcMin", "Step", _xdhPreCalcMinStep);
        saveField(changes, "crypto.edh.precalcMin", "Min", _edhPrecalcMinMin);
        saveField(changes, "crypto.edh.precalcMin", "Max", _edhPrecalcMinMax);
        saveField(changes, "crypto.edh.precalcMin", "Step", _edhPrecalcMinStep);
        saveField(changes, "crypto.mlkem.precalcMin", "Min", _mlkemPrecalcMinMin);
        saveField(changes, "crypto.mlkem.precalcMin", "Max", _mlkemPrecalcMinMax);
        saveField(changes, "crypto.mlkem.precalcMin", "Step", _mlkemPrecalcMinStep);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Min", _ntcpThreadsMin);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Max", _ntcpThreadsMax);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Step", _ntcpThreadsStep);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Min", _ntcpQueueCapacityMin);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Max", _ntcpQueueCapacityMax);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Step", _ntcpQueueCapacityStep);
        saveField(changes, "udp.packetHandler.maxThreads", "Min", _udpHandlerThreadsMin);
        saveField(changes, "udp.packetHandler.maxThreads", "Max", _udpHandlerThreadsMax);
        saveField(changes, "udp.packetHandler.maxThreads", "Step", _udpHandlerThreadsStep);
        saveField(changes, "router.peerOutboundQueueSize", "Min", _peerOutboundQueueMin);
        saveField(changes, "router.peerOutboundQueueSize", "Max", _peerOutboundQueueMax);
        saveField(changes, "router.peerOutboundQueueSize", "Step", _peerOutboundQueueStep);

        // Router Core
        saveField(changes, "router.transitThrottleFactor", "Min", _transitThrottleFactorMin);
        saveField(changes, "router.transitThrottleFactor", "Max", _transitThrottleFactorMax);
        saveField(changes, "router.transitThrottleFactor", "Step", _transitThrottleFactorStep);
        saveField(changes, "router.throttleRejectExponent", "Min", _throttleRejectExponentMin);
        saveField(changes, "router.throttleRejectExponent", "Max", _throttleRejectExponentMax);
        saveField(changes, "router.throttleRejectExponent", "Step", _throttleRejectExponentStep);
        saveField(changes, "router.maxParticipatingTunnels", "Min", _maxParticipatingTunnelsMin);
        saveField(changes, "router.maxParticipatingTunnels", "Max", _maxParticipatingTunnelsMax);
        saveField(changes, "router.maxParticipatingTunnels", "Step", _maxParticipatingTunnelsStep);
        saveField(changes, "router.buildHandlerMaxQueue", "Min", _buildHandlerMaxQueueMin);
        saveField(changes, "router.buildHandlerMaxQueue", "Max", _buildHandlerMaxQueueMax);
        saveField(changes, "router.buildHandlerMaxQueue", "Step", _buildHandlerMaxQueueStep);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Min", _goodDeficitThrottleMin);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Max", _goodDeficitThrottleMax);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Step", _goodDeficitThrottleStep);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Min", _perTunnelBweDivisorMin);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Max", _perTunnelBweDivisorMax);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Step", _perTunnelBweDivisorStep);
        saveField(changes, "router.tunnelGrowthFactor", "Min", _tunnelGrowthFactorMin);
        saveField(changes, "router.tunnelGrowthFactor", "Max", _tunnelGrowthFactorMax);
        saveField(changes, "router.tunnelGrowthFactor", "Step", _tunnelGrowthFactorStep);
        saveField(changes, "i2ptunnel.serverHandler.threads", "Min", _threadsMin);
        saveField(changes, "i2ptunnel.serverHandler.threads", "Max", _threadsMax);
        saveField(changes, "i2ptunnel.serverHandler.threads", "Step", _threadsStep);

        // Streaming congestion
        saveField(changes, "i2p.streaming.maxRTO", "Min", _maxRTOMin);
        saveField(changes, "i2p.streaming.maxRTO", "Max", _maxRTOMax);
        saveField(changes, "i2p.streaming.maxRTO", "Step", _maxRTOStep);
        saveField(changes, "i2p.streaming.maxResendDelay", "Min", _maxResendDelayMin);
        saveField(changes, "i2p.streaming.maxResendDelay", "Max", _maxResendDelayMax);
        saveField(changes, "i2p.streaming.maxResendDelay", "Step", _maxResendDelayStep);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Min", _maxRetransmissionsMin);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Max", _maxRetransmissionsMax);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Step", _maxRetransmissionsStep);
        saveField(changes, "i2p.streaming.maxRtt", "Min", _maxRttMin);
        saveField(changes, "i2p.streaming.maxRtt", "Max", _maxRttMax);
        saveField(changes, "i2p.streaming.maxRtt", "Step", _maxRttStep);
        saveField(changes, "i2p.streaming.initialResendDelay", "Min", _initialResendDelayMin);
        saveField(changes, "i2p.streaming.initialResendDelay", "Max", _initialResendDelayMax);
        saveField(changes, "i2p.streaming.initialResendDelay", "Step", _initialResendDelayStep);
        saveField(changes, "i2p.streaming.immediateAckDelay", "Min", _immediateAckDelayMin);
        saveField(changes, "i2p.streaming.immediateAckDelay", "Max", _immediateAckDelayMax);
        saveField(changes, "i2p.streaming.immediateAckDelay", "Step", _immediateAckDelayStep);

        // NetDB
        saveField(changes, "netdb.searchLimit", "Min", _netDBSearchLimitMin);
        saveField(changes, "netdb.searchLimit", "Max", _netDBSearchLimitMax);
        saveField(changes, "netdb.searchLimit", "Step", _netDBSearchLimitStep);
        saveField(changes, "netdb.maxConcurrent", "Min", _netDBMaxConcurrentMin);
        saveField(changes, "netdb.maxConcurrent", "Max", _netDBMaxConcurrentMax);
        saveField(changes, "netdb.maxConcurrent", "Step", _netDBMaxConcurrentStep);
        saveField(changes, "netdb.singleSearchTime", "Min", _netDBSingleSearchTimeMin);
        saveField(changes, "netdb.singleSearchTime", "Max", _netDBSingleSearchTimeMax);
        saveField(changes, "netdb.singleSearchTime", "Step", _netDBSingleSearchTimeStep);

        // Transport
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Min", _maxConcurrentEstablishMin);
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Max", _maxConcurrentEstablishMax);
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Step", _maxConcurrentEstablishStep);

        // Peer management
        saveField(changes, "profileOrganizer.maxProfiles", "Min", _maxProfilesMin);
        saveField(changes, "profileOrganizer.maxProfiles", "Max", _maxProfilesMax);
        saveField(changes, "profileOrganizer.maxProfiles", "Step", _maxProfilesStep);
        saveField(changes, "profileOrganizer.minFastPeers", "Min", _minFastPeersMin);
        saveField(changes, "profileOrganizer.minFastPeers", "Max", _minFastPeersMax);
        saveField(changes, "profileOrganizer.minFastPeers", "Step", _minFastPeersStep);
        saveField(changes, "profileOrganizer.maxFastPeers", "Min", _maxFastPeersMin);
        saveField(changes, "profileOrganizer.maxFastPeers", "Max", _maxFastPeersMax);
        saveField(changes, "profileOrganizer.maxFastPeers", "Step", _maxFastPeersStep);
        saveField(changes, "profileOrganizer.minHighCapacityPeers", "Min", _minHighCapPeersMin);
        saveField(changes, "profileOrganizer.minHighCapacityPeers", "Max", _minHighCapPeersMax);
        saveField(changes, "profileOrganizer.minHighCapacityPeers", "Step", _minHighCapPeersStep);
        saveField(changes, "profileOrganizer.maxHighCapacityPeers", "Min", _maxHighCapPeersMin);
        saveField(changes, "profileOrganizer.maxHighCapacityPeers", "Max", _maxHighCapPeersMax);
        saveField(changes, "profileOrganizer.maxHighCapacityPeers", "Step", _maxHighCapPeersStep);

        // Build timeouts
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Min", _buildRequestTimeoutMin);
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Max", _buildRequestTimeoutMax);
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Step", _buildRequestTimeoutStep);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Min", _buildFirstHopTimeoutMin);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Max", _buildFirstHopTimeoutMax);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Step", _buildFirstHopTimeoutStep);

        // Factory defaults (persisted for auto-revert baseline)
        saveField(changes, "ACK_FREQUENCY", "Default", _ackFrequencyDefault);
        saveField(changes, "DATA_MESSAGE_TIMEOUT", "Default", _dataMessageTimeoutDefault);
        saveField(changes, "MAX_OB_ESTABLISH_TIME", "Default", _obEstablishTimeDefault);
        saveField(changes, "MAX_IB_ESTABLISH_TIME", "Default", _ibEstablishTimeDefault);
        saveField(changes, "REQUEUE_TIME", "Default", _requeueTimeDefault);
        saveField(changes, "REPLENISH_FREQUENCY", "Default", _replenishFrequencyDefault);
        saveField(changes, "SELECTOR_LOOP_DELAY", "Default", _selectorLoopDelayDefault);
        saveField(changes, "MAX_OB_MSGS_PER_PUMP", "Default", _obMsgsPerPumpDefault);
        saveField(changes, "MAX_IB_MSGS_PER_PUMP", "Default", _ibMsgsPerPumpDefault);
        saveField(changes, "INITIAL_WINDOW_SIZE", "Default", _initialWindowSizeDefault);
        saveField(changes, "INITIAL_RTO", "Default", _initialRTODefault);
        saveField(changes, "INITIAL_ACK_DELAY", "Default", _initialAckDelayDefault);
        saveField(changes, "PASSIVE_FLUSH_DELAY", "Default", _passiveFlushDelayDefault);
        saveField(changes, "CLIENT_WRITER_QUEUE_SIZE", "Default", _writerQueueSizeDefault);
        saveField(changes, "CODEL_TARGET", "Default", _codelTargetDefault);
        saveField(changes, "CODEL_INTERVAL", "Default", _codelIntervalDefault);
        saveField(changes, "WESTWOOD_DECAY_FACTOR", "Default", _westwoodDecayFactorDefault);
        saveField(changes, "i2p.streaming.maxSlowStartWindow", "Default", _maxSlowStartWindowDefault);
        saveField(changes, "crypto.x25519.precalcMin", "Default", _xdhPreCalcMinDefault);
        saveField(changes, "ntcp.sendFinisher.maxThreads", "Default", _ntcpThreadsDefault);
        saveField(changes, "ntcp.sendFinisher.queueCapacity", "Default", _ntcpQueueCapacityDefault);
        saveField(changes, "udp.packetHandler.maxThreads", "Default", _udpHandlerThreadsDefault);
        saveField(changes, "router.peerOutboundQueueSize", "Default", _peerOutboundQueueDefault);
        saveField(changes, "router.transitThrottleFactor", "Default", _transitThrottleFactorDefault);
        saveField(changes, "router.throttleRejectExponent", "Default", _throttleRejectExponentDefault);
        saveField(changes, "router.maxParticipatingTunnels", "Default", _maxParticipatingTunnelsDefault);
        saveField(changes, "router.buildHandlerMaxQueue", "Default", _buildHandlerMaxQueueDefault);
        saveField(changes, "i2p.tunnel.goodDeficitThrottle", "Default", _goodDeficitThrottleDefault);
        saveField(changes, "router.tunnel.perTunnelBweDivisor", "Default", _perTunnelBweDivisorDefault);
        saveField(changes, "router.tunnelGrowthFactor", "Default", _tunnelGrowthFactorDefault);
        saveField(changes, "i2ptunnel.serverHandler.threads", "Default", _threadsDefault);
        saveField(changes, "i2p.streaming.maxRTO", "Default", _maxRTODefault);
        saveField(changes, "i2p.streaming.maxResendDelay", "Default", _maxResendDelayDefault);
        saveField(changes, "i2p.streaming.maxRetransmissions", "Default", _maxRetransmissionsDefault);
        saveField(changes, "netdb.searchLimit", "Default", _netDBSearchLimitDefault);
        saveField(changes, "netdb.maxConcurrent", "Default", _netDBMaxConcurrentDefault);
        saveField(changes, "netdb.singleSearchTime", "Default", _netDBSingleSearchTimeDefault);
        saveField(changes, "i2np.udp.maxConcurrentEstablish", "Default", _maxConcurrentEstablishDefault);
        saveField(changes, "profileOrganizer.maxProfiles", "Default", _maxProfilesDefault);
        saveField(changes, "profileOrganizer.minFastPeers", "Default", _minFastPeersDefault);
        saveField(changes, "profileOrganizer.maxFastPeers", "Default", _maxFastPeersDefault);
        saveField(changes, "profileOrganizer.minHighCapacityPeers", "Default", _minHighCapPeersDefault);
        saveField(changes, "profileOrganizer.maxHighCapacityPeers", "Default", _maxHighCapPeersDefault);
        saveField(changes, "i2p.tunnel.build.requestTimeout", "Default", _buildRequestTimeoutDefault);
        saveField(changes, "i2p.tunnel.build.firstHopTimeout", "Default", _buildFirstHopTimeoutDefault);
        saveField(changes, "i2p.streaming.minResendDelay", "Default", _minResendDelayDefault);
        saveField(changes, "i2p.streaming.congestionAvoidanceGrowthRateFactor", "Default", _congestionAvoidanceGrowthDefault);
        saveField(changes, "i2p.streaming.slowStartGrowthRateFactor", "Default", _slowStartGrowthDefault);
        saveField(changes, "i2p.streaming.maxRtt", "Default", _maxRttDefault);
        saveField(changes, "i2p.streaming.initialResendDelay", "Default", _initialResendDelayDefault);
        saveField(changes, "i2p.streaming.immediateAckDelay", "Default", _immediateAckDelayDefault);
        saveField(changes, "crypto.edh.precalcMin", "Default", _edhPrecalcMinDefault);
        saveField(changes, "crypto.mlkem.precalcMin", "Default", _mlkemPrecalcMinDefault);

        // Process auto-tuning overrides (checkbox toggle)
        Tuner tuner = getTuner();
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
            applyOverride(tuner, "i2ptunnel.serverHandler.threads", _threadsOverride);
            applyOverride(tuner, "i2p.streaming.maxRTO", _maxRTOOverride);
            applyOverride(tuner, "i2p.streaming.maxResendDelay", _maxResendDelayOverride);
            applyOverride(tuner, "i2p.streaming.maxRetransmissions", _maxRetransmissionsOverride);
            applyOverride(tuner, "i2p.streaming.minResendDelay", _minResendDelayOverride);
            applyOverride(tuner, "i2p.streaming.congestionAvoidanceGrowthRateFactor", _congestionAvoidanceGrowthOverride);
            applyOverride(tuner, "i2p.streaming.slowStartGrowthRateFactor", _slowStartGrowthOverride);
            applyOverride(tuner, "i2p.streaming.maxRtt", _maxRttOverride);
            applyOverride(tuner, "i2p.streaming.initialResendDelay", _initialResendDelayOverride);
            applyOverride(tuner, "i2p.streaming.immediateAckDelay", _immediateAckDelayOverride);
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
            if (tuner != null) {
                Tuner.AutotuneConfig autotune = tuner.getAutotune();
                for (Map.Entry<String, String> entry : changes.entrySet()) {
                    autotune.setProperty(entry.getKey(), entry.getValue());
                }
                autotune.forceSave();
                addFormNotice(_t("Tuning ranges saved — changes take effect immediately"));
            } else {
                addFormNotice(_t("No changes to save"));
            }
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
                           String value) {
        if (value == null || value.isEmpty())
            return;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            addFormError(_t("Invalid value") + ": " + param + "." + field + " = " + value);
            return;
        }
        String key = param + "." + field.toLowerCase();
        changes.put(key, String.valueOf(parsed));
    }

    /**
     * Build a map of property deletions to reset all tuner ranges to defaults.
     * @return the reset changes
     */
    private Map<String, String> getResetChanges() {
        Map<String, String> deletions = new HashMap<>();
        String[] params = {
            "ACK_FREQUENCY", "DATA_MESSAGE_TIMEOUT", "MAX_OB_ESTABLISH_TIME", "MAX_IB_ESTABLISH_TIME",
            "REQUEUE_TIME", "REPLENISH_FREQUENCY", "SELECTOR_LOOP_DELAY",
            "MAX_OB_MSGS_PER_PUMP", "MAX_IB_MSGS_PER_PUMP",
            "INITIAL_WINDOW_SIZE", "INITIAL_RTO", "INITIAL_ACK_DELAY", "PASSIVE_FLUSH_DELAY",
            "CLIENT_WRITER_QUEUE_SIZE",
            "CODEL_TARGET", "CODEL_INTERVAL", "WESTWOOD_DECAY_FACTOR",
            "crypto.x25519.precalcMin", "crypto.edh.precalcMin", "crypto.mlkem.precalcMin",
            "ntcp.sendFinisher.maxThreads", "ntcp.sendFinisher.queueCapacity",
            "udp.packetHandler.maxThreads",
            "router.peerOutboundQueueSize", "router.transitThrottleFactor", "router.throttleRejectExponent",
            "router.maxParticipatingTunnels", "router.buildHandlerMaxQueue",
            "i2p.tunnel.goodDeficitThrottle", "router.tunnel.perTunnelBweDivisor", "router.tunnelGrowthFactor",
            "i2ptunnel.serverHandler.threads",
            "i2p.streaming.maxSlowStartWindow", "i2p.streaming.maxRTO", "i2p.streaming.maxResendDelay",
            "i2p.streaming.maxRetransmissions", "i2p.streaming.minResendDelay",
            "i2p.streaming.congestionAvoidanceGrowthRateFactor", "i2p.streaming.slowStartGrowthRateFactor",
            "netdb.searchLimit", "netdb.maxConcurrent", "netdb.singleSearchTime",
            "i2np.udp.maxConcurrentEstablish",
            "profileOrganizer.maxProfiles", "profileOrganizer.minFastPeers",
            "i2p.tunnel.build.requestTimeout", "i2p.tunnel.build.firstHopTimeout"
        };
        for (String param : params) {
            deletions.put(PREFIX + param + ".min", null);
            deletions.put(PREFIX + param + ".max", null);
            deletions.put(PREFIX + param + ".step", null);
            deletions.put(PREFIX + param + ".default", null);
            deletions.put(PREFIX + param + ".value", null);
        }
        return deletions;
    }

    /**
     * Apply an auto-tuning override.
     * @param tuner the Tuner instance
     * @param paramName the Tuner param name
     * @param value form value: "-1" = auto, numeric = manual lock
     */
    private void applyOverride(Tuner tuner, String paramName, String value) {
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
     * @return the tuner
     */
    private Tuner getTuner() {
        if (_context == null) return null;
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) return null;
        SortedMap<String, Transport> transports = cs.getTransports();
        Transport udp = transports.get(UDPTransport.STYLE);
        if (udp instanceof UDPTransport)
            return ((UDPTransport) udp).getTuner();
        return null;
    }

    /**
     * Read a tuner property with a default.
     * Used by TuningHelper for display.
     * @return the prop
     */
    public static String getProp(RouterContext ctx,
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
    private String _threadsOverride;
    private String _maxRTOOverride;
    private String _maxResendDelayOverride;
    private String _maxRetransmissionsOverride;
    private String _minResendDelayOverride;
    private String _congestionAvoidanceGrowthOverride;
    private String _slowStartGrowthOverride;
    private String _maxRttOverride;
    private String _initialResendDelayOverride;
    private String _immediateAckDelayOverride;
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

