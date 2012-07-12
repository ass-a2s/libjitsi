/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.jmfext.media.renderer.audio;

import java.beans.*;
import java.io.*;
import java.lang.reflect.*;

import javax.media.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.pulseaudio.*;

/**
 * Implements an audio <tt>Renderer</tt> which uses PulseAudio.
 *
 * @author Lyubomir Marinov
 */
public class PulseAudioRenderer
    extends AbstractAudioRenderer
{
    /**
     * The human-readable <tt>PlugIn</tt> name of the
     * <tt>PulseAudioRenderer</tt> instances.
     */
    private static final String PLUGIN_NAME = "PulseAudio Renderer";

    private static final boolean SOFTWARE_GAIN = false;

    private static final Format[] SUPPORTED_INPUT_FORMATS
        = new Format[]
        {
            new AudioFormat(
                    AudioFormat.LINEAR,
                    Format.NOT_SPECIFIED /* sampleRate */,
                    16,
                    Format.NOT_SPECIFIED /* channels */,
                    AudioFormat.LITTLE_ENDIAN,
                    AudioFormat.SIGNED,
                    Format.NOT_SPECIFIED /* frameSizeInBits */,
                    Format.NOT_SPECIFIED /* frameRate */,
                    Format.byteArray)
        };

    private int channels;

    private boolean corked = true;

    private long cvolume;

    private final GainControl gainControl;

    private float gainControlLevel;

    private final String mediaRole;

    /*
     * TODO The field pulseAudioSystem has been introduced prior to
     * AbstractAudioSystem and its field audioSystem. It would be a good idea to
     * remove the field pulseAudioSystem in order to reduce the memory footprint
     * of the PulseAudioRenderer instances.
     */
    private final PulseAudioSystem pulseAudioSystem;

    private long stream;

    private final PA.stream_request_cb_t writeCallback
        = new PA.stream_request_cb_t()
        {
            public void callback(long s, int nbytes)
            {
                pulseAudioSystem.signalMainloop(false);
            }
        };

    /**
     * Initializes a new <tt>PulseAudioRenderer</tt> instance.
     */
    public PulseAudioRenderer()
    {
        this(null);
    }

    public PulseAudioRenderer(String mediaRole)
    {
        super(PulseAudioSystem.getPulseAudioSystem());

        pulseAudioSystem = (PulseAudioSystem) audioSystem;
        if (pulseAudioSystem == null)
            throw new IllegalStateException("pulseAudioSystem");

        this.mediaRole
            = (mediaRole == null)
                ? PulseAudioSystem.MEDIA_ROLE_PHONE
                : mediaRole;

        if (PulseAudioSystem.MEDIA_ROLE_PHONE.equals(this.mediaRole))
        {
            /*
             * XXX The Renderer implementations are probed for their
             * supportedInputFormats during the initialization of
             * MediaServiceImpl so the latter may not be available at this time.
             * Which is not much of a problem given than the GainControl is of
             * no interest during the probing of the supportedInputFormats.
             */
            MediaServiceImpl mediaServiceImpl
                = NeomediaServiceUtils.getMediaServiceImpl();

            gainControl
                = (mediaServiceImpl == null)
                    ? null
                    : (GainControl) mediaServiceImpl.getOutputVolumeControl();
        }
        else
            gainControl = null;
    }

    public void close()
    {
        pulseAudioSystem.lockMainloop();
        try
        {
            long stream = this.stream;

            if (stream != 0)
            {
                try
                {
                    stopWithMainloopLock();
                }
                finally
                {
                    long cvolume = this.cvolume;

                    this.cvolume = 0;
                    this.stream = 0;

                    corked = true;

                    pulseAudioSystem.signalMainloop(false);

                    if (cvolume != 0)
                        PA.cvolume_free(cvolume);
                    PA.stream_disconnect(stream);
                    PA.stream_unref(stream);
                }
            }

            super.close();
        }
        finally
        {
            pulseAudioSystem.unlockMainloop();
        }
    }

    private void cork(boolean b)
    {
        try
        {
            PulseAudioSystem.corkStream(stream, b);
            corked = b;
        }
        catch (IOException ioe)
        {
            throw new UndeclaredThrowableException(ioe);
        }
        finally
        {
            pulseAudioSystem.signalMainloop(false);
        }
    }

    private String getLocatorDev()
    {
        MediaLocator locator = getLocator();
        String locatorDev;

        if (locator == null)
            locatorDev = null;
        else
        {
            locatorDev = locator.getRemainder();
            if ((locatorDev != null) && (locatorDev.length() <= 0))
                locatorDev = null;
        }
        return locatorDev;
    }

    public String getName()
    {
        return PLUGIN_NAME;
    }

    public Format[] getSupportedInputFormats()
    {
        return SUPPORTED_INPUT_FORMATS.clone();
    }

    public void open()
        throws ResourceUnavailableException
    {
        pulseAudioSystem.lockMainloop();
        try
        {
            openWithMainloopLock();

            super.open();
        }
        finally
        {
            pulseAudioSystem.unlockMainloop();
        }
    }

    private void openWithMainloopLock()
        throws ResourceUnavailableException
    {
        if (stream != 0)
            return;

        AudioFormat format = this.inputFormat;
        int sampleRate = (int) format.getSampleRate();
        int channels = format.getChannels();
        int sampleSizeInBits = format.getSampleSizeInBits();

        if ((sampleRate == Format.NOT_SPECIFIED)
                && (MediaUtils.MAX_AUDIO_SAMPLE_RATE
                        != Format.NOT_SPECIFIED))
            sampleRate = (int) MediaUtils.MAX_AUDIO_SAMPLE_RATE;
        if (channels == Format.NOT_SPECIFIED)
            channels = 1;
        if (sampleSizeInBits == Format.NOT_SPECIFIED)
            sampleSizeInBits = 16;

        long stream = 0;
        Throwable exception = null;

        try
        {
            stream
                = pulseAudioSystem.createStream(
                        sampleRate,
                        channels,
                        getClass().getName(),
                        mediaRole);
            this.channels = channels;
        }
        catch (IllegalStateException ise)
        {
            exception = ise;
        }
        catch (RuntimeException re)
        {
            exception = re;
        }
        if (exception != null)
        {
            ResourceUnavailableException rue
                = new ResourceUnavailableException();

            rue.initCause(exception);
            throw rue;
        }
        if (stream == 0)
            throw new ResourceUnavailableException("stream");

        try
        {
            long attr
                = PA.buffer_attr_new(
                        -1,
                        2 /* millis / 10 */
                            * (sampleRate / 100)
                            * channels
                            * (sampleSizeInBits / 8),
                        -1,
                        -1,
                        -1);

            if (attr == 0)
                throw new ResourceUnavailableException("pa_buffer_attr_new");

            try
            {
                Runnable stateCallback
                    = new Runnable()
                    {
                        public void run()
                        {
                            pulseAudioSystem.signalMainloop(false);
                        }
                    };

                PA.stream_set_state_callback(
                        stream,
                        stateCallback);
                PA.stream_connect_playback(
                        stream,
                        getLocatorDev(),
                        attr,
                        PA.STREAM_ADJUST_LATENCY
                            | PA.STREAM_START_CORKED,
                        0,
                        0);

                try
                {
                    if (attr != 0)
                    {
                        PA.buffer_attr_free(attr);
                        attr = 0;
                    }

                    int state
                        = pulseAudioSystem.waitForStreamState(
                                stream,
                                PA.STREAM_READY);

                    if (state != PA.STREAM_READY)
                        throw new ResourceUnavailableException("stream.state");

                    PA.stream_set_write_callback(
                            stream,
                            writeCallback);

                    if (!SOFTWARE_GAIN && (gainControl != null))
                    {
                        cvolume = PA.cvolume_new();

                        boolean freeCvolume = true;

                        try
                        {
                            float gainControlLevel = gainControl.getLevel();

                            setStreamVolume(stream, gainControlLevel);
                            this.gainControlLevel = gainControlLevel;
                            freeCvolume = false;
                        }
                        finally
                        {
                            if (freeCvolume)
                            {
                                PA.cvolume_free(cvolume);
                                cvolume = 0;
                            }
                        }
                    }

                    this.stream = stream;
                }
                finally
                {
                    if (this.stream == 0)
                        PA.stream_disconnect(stream);
                }
            }
            finally
            {
                if (attr != 0)
                    PA.buffer_attr_free(attr);
            }
        }
        finally
        {
            if (this.stream == 0)
                PA.stream_unref(stream);
        }
    }

    /**
     * Notifies this instance that the value of the
     * {@link AudioSystem#PROP_PLAYBACK_DEVICE} property of its associated
     * <tt>AudioSystem</tt> has changed.
     *
     * @param event a <tt>PropertyChangeEvent</tt> which specifies details about
     * the change such as the name of the property and its old and new values
     */
    @Override
    protected void playbackDevicePropertyChange(PropertyChangeEvent event)
    {
        /*
         * FIXME Disabled due to freezes reported by Vincent Lucas and Kertesz
         * Laszlo on the dev mailing list.
         */
//        pulseAudioSystem.lockMainloop();
//        try
//        {
//            boolean open = (this.stream != 0);
//
//            if (open)
//            {
//                /*
//                 * The close method will stop this Renderer if it is currently
//                 * started.
//                 */
//                boolean start = !this.corked;
//
//                close();
//
//                try
//                {
//                    open();
//                }
//                catch (ResourceUnavailableException rue)
//                {
//                    throw new UndeclaredThrowableException(rue);
//                }
//
//                if (start)
//                    start();
//            }
//        }
//        finally
//        {
//            pulseAudioSystem.unlockMainloop();
//        }
    }

    public int process(Buffer buffer)
    {
        if (buffer.isDiscard())
            return BUFFER_PROCESSED_OK;
        if (buffer.getLength() <= 0)
            return BUFFER_PROCESSED_OK;

        int ret;

        pulseAudioSystem.lockMainloop();
        try
        {
            ret = processWithMainloopLock(buffer);
        }
        finally
        {
            pulseAudioSystem.unlockMainloop();
        }
        if ((ret != BUFFER_PROCESSED_FAILED) && (buffer.getLength() > 0))
            ret |= INPUT_BUFFER_NOT_CONSUMED;

        return ret;
    }

    private int processWithMainloopLock(Buffer buffer)
    {
        if ((stream == 0) || corked)
            return BUFFER_PROCESSED_FAILED;

        int writableSize = PA.stream_writable_size(stream);
        int ret;

        if (writableSize <= 0)
        {
            pulseAudioSystem.waitMainloop();
            ret = BUFFER_PROCESSED_OK;
        }
        else
        {
            byte[] data = (byte[]) buffer.getData();
            int offset = buffer.getOffset();
            int length = buffer.getLength();

            if (length < writableSize)
                writableSize = length;

            if (gainControl != null)
            {
                if (SOFTWARE_GAIN || (cvolume == 0))
                {
                    if (length > 0)
                    {
                        AbstractVolumeControl.applyGain(
                                gainControl,
                                data, offset, writableSize);
                    }
                }
                else
                {
                    float gainControlLevel = gainControl.getLevel();

                    if (this.gainControlLevel != gainControlLevel)
                    {
                        this.gainControlLevel = gainControlLevel;
                        setStreamVolume(stream, gainControlLevel);
                    }
                }
            }

            int writtenSize
                = PA.stream_write(
                        stream,
                        data, offset, writableSize,
                        null,
                        0,
                        PA.SEEK_RELATIVE);

            if (writtenSize < 0)
                ret = BUFFER_PROCESSED_FAILED;
            else
            {
                ret = BUFFER_PROCESSED_OK;
                buffer.setLength(length - writtenSize);
                buffer.setOffset(offset + writtenSize);
            }
        }

        return ret;
    }

    private void setStreamVolume(long stream, float level)
    {
        int volume
            = PA.sw_volume_from_linear(
                    level * (AbstractVolumeControl.MAX_VOLUME_PERCENT / 100));

        PA.cvolume_set(cvolume, channels, volume);

        long o
            = PA.context_set_sink_input_volume(
                    pulseAudioSystem.getContext(),
                    PA.stream_get_index(stream),
                    cvolume,
                    null);

        if (o != 0)
            PA.operation_unref(o);
    }

    public void start()
    {
        pulseAudioSystem.lockMainloop();
        try
        {
            if (stream == 0)
            {
                try
                {
                    openWithMainloopLock();
                }
                catch (ResourceUnavailableException rue)
                {
                    throw new UndeclaredThrowableException(rue);
                }
            }

            cork(false);
        }
        finally
        {
            pulseAudioSystem.unlockMainloop();
        }
    }

    public void stop()
    {
        pulseAudioSystem.lockMainloop();
        try
        {
            stopWithMainloopLock();
        }
        finally
        {
            pulseAudioSystem.unlockMainloop();
        }
    }

    private void stopWithMainloopLock()
    {
        if (stream != 0)
            cork(true);
    }
}
