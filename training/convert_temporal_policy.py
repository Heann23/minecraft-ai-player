"""Explicit separate-catalog extension of a trained native v7 actor.

Preserves all tensors/Adam/RNG/counters but changes probabilities for running
actions for temporal conversion. --goal-interaction instead adds zero cross
weights and preserves probabilities. Neither is new learning or learned ability.
"""
import argparse,copy,hashlib,json
from pathlib import Path
import brain_session,checkpoint_store
from model_catalog import ModelCatalog,verify_state,model_name
from train_paper_ppo import resume_checkpoint
from continuation_policy import SELECTION
from rl_actor import ActorCritic

def convert(source: Path, output: Path, name: str, *, goal_interaction=False):
    state=brain_session.load(source);verify_state(state)
    if state['contract']!='minecraft-primitive-candidates/v7' or state.get('contextUpgrade') or state['initializationOnly'] or state['pendingRolloutTransitions']:
        raise ValueError('conversion requires trained native v7 with no pending; originals preserved')
    if output.exists():raise ValueError('conversion output must be new')
    cp=Path(state['checkpoint']);model,parent=resume_checkpoint(cp)
    if not goal_interaction and model.selection!='flat':raise ValueError('source must use flat selection')
    if goal_interaction and (cp.parent.parent.parent/'conversion-evidence.json').exists():
        raise ValueError('materialized native training checkpoint required; conversion chains are not implicit resumes')
    checkpoint=json.loads(cp.read_bytes());report=json.loads((cp.parent/'training-report.json').read_bytes())
    before=copy.deepcopy(model.optimizer_state())
    if goal_interaction:model.enable_goal_interaction()
    else:model.selection=SELECTION
    after=model.optimizer_state()
    if goal_interaction:ActorCritic.verify_zero_goal_extension(before,after)
    else:assert {k:v for k,v in after.items() if k!='selection'}==before
    raw=cp.parent.parent.parent/'paper-rollouts.jsonl' if report.get('rolloutFile') else cp.parent/'paper-rollouts.jsonl'
    payload=raw.read_bytes()[:report['rolloutBytesUsed']]
    if hashlib.sha256(payload).hexdigest()!=checkpoint['rolloutSha256']:raise ValueError('source prefix mismatch')
    output.mkdir(parents=True,exist_ok=False)
    checkpoint_store.durable_replace(output/'paper-rollouts.jsonl',payload)
    checkpoint['optimizer']=after
    artifact=model.artifact(environment_steps=state['trainedEnvironmentSteps'],training_run=checkpoint['run'],rollout_sha256=checkpoint['rolloutSha256'])
    artifact.update(brainId=state['brainId'],modelVersion=model.version,gradientUpdates=model.updates,trainingStartState='resume')
    bundle=checkpoint_store.publish(output,artifact,checkpoint,report)
    destination=bundle/'optimizer-checkpoint.json'
    migrated=dict(state,checkpoint=str(destination.resolve()),checkpointSha256=hashlib.sha256(destination.read_bytes()).hexdigest(),modelName=model_name(name))
    verify_state(migrated)
    brain_session.save(output/'runtime-session.json',migrated,recoverable=True)
    revision=ModelCatalog(output/'catalog').save(name,migrated)
    evidence={'schema':'minecraft-temporal-policy-conversion/v1','sourceSession':state,'convertedSession':migrated,'selection':SELECTION,'allTensorsAdamCheckpointRngAndCountersPreserved':True,'sessionRngPreserved':state['rng']==migrated['rng'],'initialProbabilitiesPreserved':False,'architecturePriorChanged':True,'newLearning':False,'abilityProven':False,'sourcePrefixSha256':hashlib.sha256(payload).hexdigest()}
    if goal_interaction:evidence.update(schema='minecraft-goal-interaction-conversion/v1',selection=model.selection,interaction=model.interaction,initialProbabilitiesPreserved=True,architecturePriorChanged=False,newZeroGoalActionTensorShape=[24,256])
    checkpoint_store.durable_replace(output/'conversion-evidence.json',checkpoint_store.encoded(evidence))
    return revision

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--name',type=model_name,required=True)
    parser.add_argument('--goal-interaction',action='store_true',help='zero-influence goal/action residual; preserve existing selection law')
    args=parser.parse_args();print(json.dumps(convert(args.session,args.output,args.name,goal_interaction=args.goal_interaction)))
