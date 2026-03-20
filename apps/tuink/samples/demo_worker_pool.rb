#!/usr/bin/env ruby
# Demo: Thread-based worker pool with queue dispatch and simple stats.
# frozen_string_literal: true

require 'thread'
require 'time'

Task = Struct.new(:id, :queue, :payload, :created_at, keyword_init: true)

class WorkerPool
  def initialize(size:, handler:)
    raise ArgumentError, 'size must be > 0' if size <= 0

    @size = size
    @handler = handler
    @queue = Queue.new
    @workers = []
    @alive = true
    @stats = {
      processed: 0,
      failed: 0,
      by_queue: Hash.new(0)
    }
    @stats_lock = Mutex.new
  end

  def start
    @size.times do |idx|
      @workers << Thread.new do
        Thread.current.name = "worker-#{idx + 1}" if Thread.current.respond_to?(:name=)
        run_loop
      end
    end
    self
  end

  def push(task)
    raise 'pool stopped' unless @alive
    @queue << task
  end

  def stop
    @alive = false
    @size.times { @queue << :__stop__ }
    @workers.each(&:join)
  end

  def snapshot
    @stats_lock.synchronize do
      {
        processed: @stats[:processed],
        failed: @stats[:failed],
        by_queue: @stats[:by_queue].dup
      }
    end
  end

  private

  def run_loop
    loop do
      item = @queue.pop
      break if item == :__stop__

      begin
        @handler.call(item)
        mark_success(item.queue)
      rescue StandardError => e
        mark_failure(item.queue)
        warn "task=#{item.id} queue=#{item.queue} failed: #{e.class}: #{e.message}"
      end
    end
  end

  def mark_success(queue_name)
    @stats_lock.synchronize do
      @stats[:processed] += 1
      @stats[:by_queue][queue_name] += 1
    end
  end

  def mark_failure(queue_name)
    @stats_lock.synchronize do
      @stats[:failed] += 1
      @stats[:by_queue][queue_name] += 1
    end
  end
end

def build_task(id, queue, payload)
  Task.new(
    id: id,
    queue: queue,
    payload: payload,
    created_at: Time.now.utc.iso8601
  )
end

handler = lambda do |task|
  sleep(0.02)
  if task.payload[:kind] == :bad
    raise "bad payload: #{task.payload.inspect}"
  end
  puts "processed task=#{task.id} queue=#{task.queue} value=#{task.payload[:value]}"
end

pool = WorkerPool.new(size: 3, handler: handler).start

queues = %w[critical default low]
20.times do |i|
  queue = queues[i % queues.size]
  kind = (i % 7).zero? ? :bad : :ok
  pool.push(build_task(i + 1, queue, { kind: kind, value: i * 10 }))
end

pool.stop
pp pool.snapshot
